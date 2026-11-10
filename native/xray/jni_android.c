//go:build android

#include <jni.h>
#include <pthread.h>

extern int WotXrayStart(char *data, int length);
extern void WotXrayStop(void);
extern int WotXrayRunning(void);

static JavaVM *vm;
static jobject vpn_owner;
static jmethodID protect_method;
static pthread_rwlock_t owner_lock = PTHREAD_RWLOCK_INITIALIZER;
static pthread_mutex_t lifecycle_lock = PTHREAD_MUTEX_INITIALIZER;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *java_vm, void *reserved) {
    (void)reserved;
    vm = java_vm;
    return JNI_VERSION_1_6;
}

int wot_protect_socket(int fd) {
    JNIEnv *env = NULL;
    int attached = 0;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        if ((*vm)->AttachCurrentThread(vm, &env, NULL) != JNI_OK) return 0;
        attached = 1;
    }
    if (env == NULL) return 0;
    pthread_rwlock_rdlock(&owner_lock);
    jboolean result = JNI_FALSE;
    if (vpn_owner != NULL) {
        result = (*env)->CallBooleanMethod(env, vpn_owner, protect_method, (jint)fd);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            result = JNI_FALSE;
        }
    }
    pthread_rwlock_unlock(&owner_lock);
    if (attached) (*vm)->DetachCurrentThread(vm);
    return result == JNI_TRUE;
}

static void clear_owner(JNIEnv *env) {
    pthread_rwlock_wrlock(&owner_lock);
    if (vpn_owner != NULL) (*env)->DeleteGlobalRef(env, vpn_owner);
    vpn_owner = NULL;
    protect_method = NULL;
    pthread_rwlock_unlock(&owner_lock);
}

JNIEXPORT jint JNICALL Java_com_dronits_wotvpn_core_NativeXray_start(
    JNIEnv *env, jobject self, jbyteArray config, jobject service) {
    (void)self;
    pthread_mutex_lock(&lifecycle_lock);
    if (vpn_owner != NULL) {
        pthread_mutex_unlock(&lifecycle_lock);
        return 2;
    }
    jclass klass = (*env)->GetObjectClass(env, service);
    jmethodID method = (*env)->GetMethodID(env, klass, "protect", "(I)Z");
    (*env)->DeleteLocalRef(env, klass);
    if (method == NULL) {
        pthread_mutex_unlock(&lifecycle_lock);
        return 4;
    }
    pthread_rwlock_wrlock(&owner_lock);
    vpn_owner = (*env)->NewGlobalRef(env, service);
    protect_method = method;
    pthread_rwlock_unlock(&owner_lock);
    if (vpn_owner == NULL) {
        pthread_mutex_unlock(&lifecycle_lock);
        return 4;
    }
    jsize length = (*env)->GetArrayLength(env, config);
    jbyte *bytes = (*env)->GetByteArrayElements(env, config, NULL);
    int result = 4;
    if (bytes != NULL) {
        result = WotXrayStart((char *)bytes, length);
        (*env)->ReleaseByteArrayElements(env, config, bytes, JNI_ABORT);
    }
    if (result != 0) clear_owner(env);
    pthread_mutex_unlock(&lifecycle_lock);
    return result;
}

JNIEXPORT void JNICALL Java_com_dronits_wotvpn_core_NativeXray_stop(JNIEnv *env, jobject self) {
    (void)self;
    pthread_mutex_lock(&lifecycle_lock);
    WotXrayStop();
    clear_owner(env);
    pthread_mutex_unlock(&lifecycle_lock);
}

JNIEXPORT jboolean JNICALL Java_com_dronits_wotvpn_core_NativeXray_isRunning(JNIEnv *env, jobject self) {
    (void)env; (void)self;
    return WotXrayRunning() ? JNI_TRUE : JNI_FALSE;
}
