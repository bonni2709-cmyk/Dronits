/* JNI adapter for pinned MIT-licensed HevSocks5Tunnel. No configuration files. */
#ifdef ANDROID
#include <jni.h>
#include <pthread.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdlib.h>
#include <time.h>
#include <errno.h>
#include "hev-main.h"

static pthread_mutex_t api_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t state_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t state_changed = PTHREAD_COND_INITIALIZER;
static pthread_t worker;
static int joinable, running, ready;

typedef struct {
    unsigned char *config;
    unsigned int length;
    int fd;
} WorkerData;

/* Called only after initialization and scheduling of the lwIP tasks. */
void wot_tunnel_ready(void) {
    pthread_mutex_lock(&state_lock);
    ready = 1;
    pthread_cond_broadcast(&state_changed);
    pthread_mutex_unlock(&state_lock);
}

static void *run_tunnel(void *ptr) {
    WorkerData *data = ptr;
    hev_socks5_tunnel_main_from_str(data->config, data->length, data->fd);
    close(data->fd);
    free(data->config);
    free(data);
    pthread_mutex_lock(&state_lock);
    running = 0;
    ready = 0;
    pthread_cond_broadcast(&state_changed);
    pthread_mutex_unlock(&state_lock);
    return NULL;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm; (void)reserved;
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL Java_com_dronits_wotvpn_core_NativeTunnel_start(
    JNIEnv *env, jobject self, jbyteArray config, jint fd) {
    (void)self;
    jboolean result = JNI_FALSE;
    pthread_mutex_lock(&api_lock);
    pthread_mutex_lock(&state_lock);
    int active = running;
    pthread_mutex_unlock(&state_lock);
    if (active) goto done;
    if (joinable) { pthread_join(worker, NULL); joinable = 0; }
    jsize length = (*env)->GetArrayLength(env, config);
    if (length <= 0 || length > 65536) goto done;
    WorkerData *data = calloc(1, sizeof(*data));
    if (!data) goto done;
    data->config = malloc((size_t)length);
    data->fd = fcntl(fd, F_DUPFD_CLOEXEC, 0);
    if (!data->config || data->fd < 0) {
        if (data->fd >= 0) close(data->fd);
        free(data->config); free(data);
        goto done;
    }
    (*env)->GetByteArrayRegion(env, config, 0, length, (jbyte *)data->config);
    if ((*env)->ExceptionCheck(env)) {
        close(data->fd); free(data->config); free(data);
        goto done;
    }
    data->length = (unsigned int)length;
    pthread_mutex_lock(&state_lock);
    running = 1;
    ready = 0;
    pthread_mutex_unlock(&state_lock);
    if (pthread_create(&worker, NULL, run_tunnel, data) != 0) {
        pthread_mutex_lock(&state_lock); running = 0; pthread_mutex_unlock(&state_lock);
        close(data->fd); free(data->config); free(data);
        goto done;
    }
    joinable = 1;
    struct timespec deadline;
    clock_gettime(CLOCK_REALTIME, &deadline);
    deadline.tv_sec += 10;
    pthread_mutex_lock(&state_lock);
    while (running && !ready) {
        if (pthread_cond_timedwait(&state_changed, &state_lock, &deadline) == ETIMEDOUT) break;
    }
    result = running && ready ? JNI_TRUE : JNI_FALSE;
    pthread_mutex_unlock(&state_lock);
    if (!result) {
        hev_socks5_tunnel_quit();
        pthread_join(worker, NULL);
        joinable = 0;
    }
done:
    pthread_mutex_unlock(&api_lock);
    return result;
}

JNIEXPORT void JNICALL Java_com_dronits_wotvpn_core_NativeTunnel_stop(JNIEnv *env, jobject self) {
    (void)env; (void)self;
    pthread_mutex_lock(&api_lock);
    if (joinable) {
        pthread_mutex_lock(&state_lock);
        int active = running;
        pthread_mutex_unlock(&state_lock);
        if (active) hev_socks5_tunnel_quit();
        pthread_join(worker, NULL);
        joinable = 0;
    }
    pthread_mutex_unlock(&api_lock);
}

JNIEXPORT jboolean JNICALL Java_com_dronits_wotvpn_core_NativeTunnel_isRunning(JNIEnv *env, jobject self) {
    (void)env; (void)self;
    pthread_mutex_lock(&state_lock);
    int active = running && ready;
    pthread_mutex_unlock(&state_lock);
    return active ? JNI_TRUE : JNI_FALSE;
}
#endif
