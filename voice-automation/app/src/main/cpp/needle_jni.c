/*
 * JNI bridge between Kotlin (NeedleNative.kt) and the Cactus Needle engine.
 *
 * Signatures follow the engine's C API as the cactus-needle Python package binds
 * it (generation 3):
 *   int  needle_load(const char *data, uint64_t size);
 *   int  needle_init(const char *system, const char *tools_json, const char *tool_index_path);
 *   int  needle_complete(const char *text, const float *audio, int n_samples,
 *                        int max_new_tokens, char *out, int out_len);
 *   int  needle_transcribe(const float *samples, int n_samples, const char *language,
 *                          const char *keywords, int word_timestamps, char *out, int out_len);
 *   void needle_reset(void);
 *   const char *needle_last_error(void);
 * If the needle.h you download disagrees, needle.h wins: fix the calls below.
 *
 * The engine holds ONE global model and conversation per process and is not
 * thread-safe. EngineService calls in here from a single thread only.
 */
#define _POSIX_C_SOURCE 200809L /* strdup, O_CLOEXEC */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <android/log.h>

#include "needle.h"

#define TAG "NeedleBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define COMPLETE_BUF (64 * 1024)     /* Python package default */
#define TRANSCRIBE_BUF (256 * 1024)  /* Python package default */

static void throw_runtime(JNIEnv *env, const char *fallback, const char *detail) {
    jclass cls = (*env)->FindClass(env, "java/lang/RuntimeException");
    if (cls == NULL) return;
    const char *msg = (detail != NULL && detail[0] != '\0') ? detail : fallback;
    (*env)->ThrowNew(env, cls, msg);
}

/* Returns the C string as a Java byte[] (real UTF-8; NewStringUTF expects
 * "modified UTF-8" and can garble characters outside the BMP). */
static jbyteArray to_bytes(JNIEnv *env, const char *s) {
    jsize len = (jsize) strlen(s);
    jbyteArray arr = (*env)->NewByteArray(env, len);
    if (arr != NULL) (*env)->SetByteArrayRegion(env, arr, 0, len, (const jbyte *) s);
    return arr;
}

static const char *last_error(void) {
    const char *e = needle_last_error();
    return e != NULL ? e : "";
}

/* Copies a Java string to a malloc'd UTF-8 C string (NULL for a null jstring). */
static char *dup_string(JNIEnv *env, jstring s) {
    if (s == NULL) return NULL;
    const char *utf = (*env)->GetStringUTFChars(env, s, NULL);
    if (utf == NULL) return NULL;
    char *copy = strdup(utf);
    (*env)->ReleaseStringUTFChars(env, s, utf);
    return copy;
}

/*
 * Loads a .cact file (Needle or Whistle). The file is memory-mapped only for the
 * duration of the call: the Python package frees its buffer right after
 * needle_load, which shows the engine keeps its own copy.
 * Returns the engine's non-negative result, or throws.
 */
JNIEXPORT jint JNICALL
Java_com_voiceauto_app_engine_NeedleNative_nativeLoad(JNIEnv *env, jclass cls, jstring jpath) {
    (void) cls;
    char *path = dup_string(env, jpath);
    if (path == NULL) { throw_runtime(env, "model path is null", NULL); return -1; }

    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) { LOGE("open failed: %s", path); free(path); throw_runtime(env, "cannot open model file", NULL); return -1; }

    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size <= 0) {
        close(fd); free(path);
        throw_runtime(env, "model file is empty or unreadable", NULL);
        return -1;
    }

    void *data = mmap(NULL, (size_t) st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (data == MAP_FAILED) { free(path); throw_runtime(env, "mmap failed", NULL); return -1; }

    int rc = needle_load((const char *) data, (uint64_t) st.st_size);
    munmap(data, (size_t) st.st_size);
    free(path);

    if (rc < 0) { throw_runtime(env, "needle_load failed", last_error()); }
    return rc;
}

/* Sets the system facts and the tool schemas. Returns the static-prefix token count. */
JNIEXPORT jint JNICALL
Java_com_voiceauto_app_engine_NeedleNative_nativeInit(JNIEnv *env, jclass cls,
                                                     jstring jsystem, jstring jtools) {
    (void) cls;
    char *system = dup_string(env, jsystem);
    char *tools = dup_string(env, jtools);
    int rc = needle_init(system != NULL ? system : "", tools != NULL ? tools : "[]", NULL);
    free(system);
    free(tools);
    if (rc < 0) { throw_runtime(env, "needle_init failed (tool prefix may not fit the context)", last_error()); }
    return rc;
}

/* Speech to text with Whistle. samples: 16 kHz mono floats in [-1, 1]. Returns JSON (UTF-8). */
JNIEXPORT jbyteArray JNICALL
Java_com_voiceauto_app_engine_NeedleNative_nativeTranscribe(JNIEnv *env, jclass cls,
                                                           jfloatArray jsamples,
                                                           jstring jlanguage,
                                                           jstring jkeywords) {
    (void) cls;
    jsize n = (*env)->GetArrayLength(env, jsamples);
    jfloat *samples = (*env)->GetFloatArrayElements(env, jsamples, NULL);
    if (samples == NULL) { throw_runtime(env, "cannot read audio", NULL); return NULL; }

    char *language = dup_string(env, jlanguage);
    char *keywords = dup_string(env, jkeywords);
    char *out = (char *) calloc(TRANSCRIBE_BUF, 1);

    int rc = -1;
    if (out != NULL) {
        rc = needle_transcribe(samples, (int) n, language, keywords, 0, out, TRANSCRIBE_BUF);
    }

    (*env)->ReleaseFloatArrayElements(env, jsamples, samples, JNI_ABORT);
    free(language);
    free(keywords);

    jbyteArray result = NULL;
    if (out == NULL) {
        throw_runtime(env, "out of memory", NULL);
    } else if (rc < 0) {
        throw_runtime(env, "needle_transcribe failed", last_error());
    } else {
        out[TRANSCRIBE_BUF - 1] = '\0';
        result = to_bytes(env, out);
    }
    free(out);
    return result;
}

/* Tool calling with Needle. Returns the engine's JSON envelope (UTF-8). */
JNIEXPORT jbyteArray JNICALL
Java_com_voiceauto_app_engine_NeedleNative_nativeComplete(JNIEnv *env, jclass cls,
                                                         jstring jtext, jint maxNewTokens) {
    (void) cls;
    char *text = dup_string(env, jtext);
    char *out = (char *) calloc(COMPLETE_BUF, 1);
    if (text == NULL || out == NULL) {
        free(text); free(out);
        throw_runtime(env, "out of memory", NULL);
        return NULL;
    }

    int rc = needle_complete(text, NULL, 0, (int) maxNewTokens, out, COMPLETE_BUF);
    free(text);
    out[COMPLETE_BUF - 1] = '\0';

    jbyteArray result = NULL;
    if (rc < 0) {
        /* On failure the engine may write its detail into the output buffer. */
        throw_runtime(env, "needle_complete failed", out[0] != '\0' ? out : last_error());
    } else {
        result = to_bytes(env, out);
    }
    free(out);
    return result;
}

/* Clears the conversation so each command starts fresh. */
JNIEXPORT void JNICALL
Java_com_voiceauto_app_engine_NeedleNative_nativeReset(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    needle_reset();
}
