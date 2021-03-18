#include "org_telegram_messenger_voip_Instance.h"

#include <jni.h>
#include <sdk/android/native_api/video/wrapper.h>
#include <VideoCapturerInterface.h>
#include <platform/android/AndroidInterface.h>
#include <platform/android/AndroidContext.h>
#include <rtc_base/ssl_adapter.h>
#include <modules/utility/include/jvm_android.h>
#include <sdk/android/native_api/base/init.h>
#include <voip/webrtc/media/base/media_constants.h>
#include <tgnet/FileLog.h>
#include <voip/tgcalls/group/GroupInstanceImpl.h>

#include <memory>

#include "pc/video_track.h"
#include "legacy/InstanceImplLegacy.h"
#include "InstanceImpl.h"
#include "reference/InstanceImplReference.h"
#include "libtgvoip/os/android/AudioOutputOpenSLES.h"
#include "libtgvoip/os/android/AudioInputOpenSLES.h"
#include "libtgvoip/os/android/JNIUtilities.h"
#include "tgcalls/VideoCaptureInterface.h"

using namespace tgcalls;

const auto RegisterTag = Register<InstanceImpl>();
const auto RegisterTagLegacy = Register<InstanceImplLegacy>();
const auto RegisterTagReference = tgcalls::Register<InstanceImplReference>();

class JavaObject {
private:
    JNIEnv *env;
    jobject obj;
    jclass clazz;

public:
    JavaObject(JNIEnv *env, jobject obj) : JavaObject(env, obj, env->GetObjectClass(obj)) {
    }

    JavaObject(JNIEnv *env, jobject obj, jclass clazz) {
        this->env = env;
        this->obj = obj;
        this->clazz = clazz;
    }

    jint getIntField(const char *name) {
        return env->GetIntField(obj, env->GetFieldID(clazz, name, "I"));
    }

    jlong getLongField(const char *name) {
        return env->GetLongField(obj, env->GetFieldID(clazz, name, "J"));
    }

    jboolean getBooleanField(const char *name) {
        return env->GetBooleanField(obj, env->GetFieldID(clazz, name, "Z"));
    }

    jdouble getDoubleField(const char *name) {
        return env->GetDoubleField(obj, env->GetFieldID(clazz, name, "D"));
    }

    jbyteArray getByteArrayField(const char *name) {
        return (jbyteArray) env->GetObjectField(obj, env->GetFieldID(clazz, name, "[B"));
    }

    jstring getStringField(const char *name) {
        return (jstring) env->GetObjectField(obj, env->GetFieldID(clazz, name, "Ljava/lang/String;"));
    }
};

struct InstanceHolder {
    std::unique_ptr<Instance> nativeInstance;
    std::unique_ptr<GroupInstanceImpl> groupNativeInstance;
    std::shared_ptr<tgcalls::VideoCaptureInterface> _videoCapture;
    std::shared_ptr<PlatformContext> _platformContext;
};

jclass TrafficStatsClass;
jclass FingerprintClass;
jclass FinalStateClass;
jclass NativeInstanceClass;
jmethodID FinalStateInitMethod;

jlong getInstanceHolderId(JNIEnv *env, jobject obj) {
    return env->GetLongField(obj, env->GetFieldID(NativeInstanceClass, "nativePtr", "J"));
}

InstanceHolder *getInstanceHolder(JNIEnv *env, jobject obj) {
    return reinterpret_cast<InstanceHolder *>(getInstanceHolderId(env, obj));
}

jint throwNewJavaException(JNIEnv *env, const char *className, const char *message) {
    return env->ThrowNew(env->FindClass(className), message);
}

jint throwNewJavaIllegalArgumentException(JNIEnv *env, const char *message) {
    return throwNewJavaException(env, "java/lang/IllegalStateException", message);
}

jbyteArray copyVectorToJavaByteArray(JNIEnv *env, const std::vector<uint8_t> &bytes) {
    unsigned int size = bytes.size();
    jbyteArray bytesArray = env->NewByteArray(size);
    env->SetByteArrayRegion(bytesArray, 0, size, (jbyte *) bytes.data());
    return bytesArray;
}

void readPersistentState(const char *filePath, PersistentState &persistentState) {
    FILE *persistentStateFile = fopen(filePath, "r");
    if (persistentStateFile) {
        fseek(persistentStateFile, 0, SEEK_END);
        auto len = static_cast<size_t>(ftell(persistentStateFile));
        fseek(persistentStateFile, 0, SEEK_SET);
        if (len < 1024 * 512 && len > 0) {
            auto *buffer = static_cast<uint8_t *>(malloc(len));
            fread(buffer, 1, len, persistentStateFile);
            persistentState.value = std::vector<uint8_t>(buffer, buffer + len);
            free(buffer);
        }
        fclose(persistentStateFile);
    }
}

void savePersistentState(const char *filePath, const PersistentState &persistentState) {
    FILE *persistentStateFile = fopen(filePath, "w");
    if (persistentStateFile) {
        fwrite(persistentState.value.data(), 1, persistentState.value.size(), persistentStateFile);
        fclose(persistentStateFile);
    }
}

NetworkType parseNetworkType(jint networkType) {
    switch (networkType) {
        case org_telegram_messenger_voip_Instance_NET_TYPE_GPRS:
            return NetworkType::Gprs;
        case org_telegram_messenger_voip_Instance_NET_TYPE_EDGE:
            return NetworkType::Edge;
        case org_telegram_messenger_voip_Instance_NET_TYPE_3G:
            return NetworkType::ThirdGeneration;
        case org_telegram_messenger_voip_Instance_NET_TYPE_HSPA:
            return NetworkType::Hspa;
        case org_telegram_messenger_voip_Instance_NET_TYPE_LTE:
            return NetworkType::Lte;
        case org_telegram_messenger_voip_Instance_NET_TYPE_WIFI:
            return NetworkType::WiFi;
        case org_telegram_messenger_voip_Instance_NET_TYPE_ETHERNET:
            return NetworkType::Ethernet;
        case org_telegram_messenger_voip_Instance_NET_TYPE_OTHER_HIGH_SPEED:
            return NetworkType::OtherHighSpeed;
        case org_telegram_messenger_voip_Instance_NET_TYPE_OTHER_LOW_SPEED:
            return NetworkType::OtherLowSpeed;
        case org_telegram_messenger_voip_Instance_NET_TYPE_DIALUP:
            return NetworkType::Dialup;
        case org_telegram_messenger_voip_Instance_NET_TYPE_OTHER_MOBILE:
            return NetworkType::OtherMobile;
        default:
            return NetworkType::Unknown;
    }
}

DataSaving parseDataSaving(JNIEnv *env, jint dataSaving) {
    switch (dataSaving) {
        case org_telegram_messenger_voip_Instance_DATA_SAVING_NEVER:
            return DataSaving::Never;
        case org_telegram_messenger_voip_Instance_DATA_SAVING_MOBILE:
            return DataSaving::Mobile;
        case org_telegram_messenger_voip_Instance_DATA_SAVING_ALWAYS:
            return DataSaving::Always;
        case org_telegram_messenger_voip_Instance_DATA_SAVING_ROAMING:
            throwNewJavaIllegalArgumentException(env, "DATA_SAVING_ROAMING is not supported");
            return DataSaving::Never;
        default:
            throwNewJavaIllegalArgumentException(env, "Unknown data saving constant: " + dataSaving);
            return DataSaving::Never;
    }
}

EndpointType parseEndpointType(JNIEnv *env, jint endpointType) {
    switch (endpointType) {
        case org_telegram_messenger_voip_Instance_ENDPOINT_TYPE_INET:
            return EndpointType::Inet;
        case org_telegram_messenger_voip_Instance_ENDPOINT_TYPE_LAN:
            return EndpointType::Lan;
        case org_telegram_messenger_voip_Instance_ENDPOINT_TYPE_TCP_RELAY:
            return EndpointType::TcpRelay;
        case org_telegram_messenger_voip_Instance_ENDPOINT_TYPE_UDP_RELAY:
            return EndpointType::UdpRelay;
        default:
            throwNewJavaIllegalArgumentException(env, std::string("Unknown endpoint type: ").append(std::to_string(endpointType)).c_str());
            return EndpointType::UdpRelay;
    }
}

jint asJavaState(const State &state) {
    switch (state) {
        case State::WaitInit:
            return org_telegram_messenger_voip_Instance_STATE_WAIT_INIT;
        case State::WaitInitAck:
            return org_telegram_messenger_voip_Instance_STATE_WAIT_INIT_ACK;
        case State::Established:
            return org_telegram_messenger_voip_Instance_STATE_ESTABLISHED;
        case State::Failed:
            return org_telegram_messenger_voip_Instance_STATE_FAILED;
        case State::Reconnecting:
            return org_telegram_messenger_voip_Instance_STATE_RECONNECTING;
    }
}

jobject asJavaTrafficStats(JNIEnv *env, const TrafficStats &trafficStats) {
    jmethodID initMethodId = env->GetMethodID(TrafficStatsClass, "<init>", "(JJJJ)V");
    return env->NewObject(TrafficStatsClass, initMethodId, (jlong) trafficStats.bytesSentWifi, (jlong) trafficStats.bytesReceivedWifi, (jlong) trafficStats.bytesSentMobile, (jlong) trafficStats.bytesReceivedMobile);
}

jobject asJavaFinalState(JNIEnv *env, const FinalState &finalState) {
    jbyteArray persistentState = copyVectorToJavaByteArray(env, finalState.persistentState.value);
    jstring debugLog = env->NewStringUTF(finalState.debugLog.c_str());
    jobject trafficStats = asJavaTrafficStats(env, finalState.trafficStats);
    auto isRatingSuggested = static_cast<jboolean>(finalState.isRatingSuggested);
    return env->NewObject(FinalStateClass, FinalStateInitMethod, persistentState, debugLog, trafficStats, isRatingSuggested);
}

jobject asJavaFingerprint(JNIEnv *env, std::string hash, std::string setup, std::string fingerprint) {
    jstring hashStr = env->NewStringUTF(hash.c_str());
    jstring setupStr = env->NewStringUTF(setup.c_str());
    jstring fingerprintStr = env->NewStringUTF(fingerprint.c_str());
    jmethodID initMethodId = env->GetMethodID(FingerprintClass, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    return env->NewObject(FingerprintClass, initMethodId, hashStr, setupStr, fingerprintStr);
}

extern "C" {

bool webrtcLoaded = false;

void initWebRTC(JNIEnv *env) {
    if (webrtcLoaded) {
        return;
    }
    JavaVM* vm;
    env->GetJavaVM(&vm);
    webrtc::InitAndroid(vm);
    webrtc::JVM::Initialize(vm);
    rtc::InitializeSSL();
    webrtcLoaded = true;

    NativeInstanceClass = static_cast<jclass>(env->NewGlobalRef(env->FindClass("org/telegram/messenger/voip/NativeInstance")));
    TrafficStatsClass = static_cast<jclass>(env->NewGlobalRef(env->FindClass("org/telegram/messenger/voip/Instance$TrafficStats")));
    FingerprintClass = static_cast<jclass>(env->NewGlobalRef(env->FindClass("org/telegram/messenger/voip/Instance$Fingerprint")));
    FinalStateClass = static_cast<jclass>(env->NewGlobalRef(env->FindClass("org/telegram/messenger/voip/Instance$FinalState")));
    FinalStateInitMethod = env->GetMethodID(FinalStateClass, "<init>", "([BLjava/lang/String;Lorg/telegram/messenger/voip/Instance$TrafficStats;Z)V");
}

JNIEXPORT jlong JNICALL Java_org_telegram_messenger_voip_NativeInstance_makeGroupNativeInstance(JNIEnv *env, jclass clazz, jobject instanceObj, jboolean highQuality) {
    initWebRTC(env);

    std::shared_ptr<PlatformContext> platformContext = std::make_shared<AndroidContext>(env, instanceObj);

    GroupInstanceDescriptor descriptor = {
            .networkStateUpdated = [platformContext](bool state) {
                tgvoip::jni::DoWithJNI([platformContext, state](JNIEnv *env) {
                    jobject globalRef = ((AndroidContext *) platformContext.get())->getJavaInstance();
                    env->CallVoidMethod(globalRef, env->GetMethodID(NativeInstanceClass, "onNetworkStateUpdated", "(Z)V"), state);
                });
            },
            .audioLevelsUpdated = [platformContext](GroupLevelsUpdate const &update) {
                tgvoip::jni::DoWithJNI([platformContext, update](JNIEnv *env) {
                    unsigned int size = update.updates.size();
                    jintArray intArray = env->NewIntArray(size);
                    jfloatArray floatArray = env->NewFloatArray(size);
                    jbooleanArray boolArray = env->NewBooleanArray(size);

                    jint intFill[size];
                    jfloat floatFill[size];
                    jboolean boolFill[size];
                    for (int a = 0; a < size; a++) {
                        intFill[a] = update.updates[a].ssrc;
                        floatFill[a] = update.updates[a].value.level;
                        boolFill[a] = update.updates[a].value.voice;
                    }
                    env->SetIntArrayRegion(intArray, 0, size, intFill);
                    env->SetFloatArrayRegion(floatArray, 0, size, floatFill);
                    env->SetBooleanArrayRegion(boolArray, 0, size, boolFill);

                    jobject globalRef = ((AndroidContext *) platformContext.get())->getJavaInstance();
                    env->CallVoidMethod(globalRef, env->GetMethodID(NativeInstanceClass, "onAudioLevelsUpdated", "([I[F[Z)V"), intArray, floatArray, boolArray);
                    env->DeleteLocalRef(intArray);
                    env->DeleteLocalRef(floatArray);
                    env->DeleteLocalRef(boolArray);
                });
            },
            .platformContext = platformContext
    };

    auto *holder = new InstanceHolder;
    holder->groupNativeInstance = std::make_unique<GroupInstanceImpl>(std::move(descriptor));
    holder->_platformContext = platformContext;
    holder->groupNativeInstance->emitJoinPayload([platformContext](const GroupJoinPayload& payload) {
        JNIEnv *env = webrtc::AttachCurrentThreadIfNeeded();
        jobjectArray array = env->NewObjectArray(payload.fingerprints.size(), FingerprintClass, 0);
        for (int a = 0; a < payload.fingerprints.size(); a++) {
            env->SetObjectArrayElement(array, a, asJavaFingerprint(env, payload.fingerprints[a].hash, payload.fingerprints[a].setup, payload.fingerprints[a].fingerprint));
        }
        jobject globalRef = ((AndroidContext *) platformContext.get())->getJavaInstance();
        env->CallVoidMethod(globalRef, env->GetMethodID(NativeInstanceClass, "onEmitJoinPayload", "(Ljava/lang/String;Ljava/lang/String;[Lorg/telegram/messenger/voip/Instance$Fingerprint;I)V"), env->NewStringUTF(payload.ufrag.c_str()), env->NewStringUTF(payload.pwd.c_str()), array, (jint) payload.ssrc);
    });
    return reinterpret_cast<jlong>(holder);
}

JNIEXPORT void JNICALL Java_org_telegram_messenger_voip_NativeInstance_setJoinResponsePayload(JNIEnv *env, jobject obj, jstring ufrag, jstring pwd, jobjectArray fingerprints, jobjectArray candidates) {
    InstanceHolder *instance = getInstanceHolder(env, obj);
    if (instance->groupNativeInstance == nullptr) {
        return;
    }
    std::vector<GroupJoinPayloadFingerprint> fingerprintsArray;
    std::vector<GroupJoinResponseCandidate> candidatesArray;

    jsize size = env->GetArrayLength(fingerprints);
    for (int i = 0; i < size; i++) {
        JavaObject fingerprintObject(env, env->GetObjectArrayElement(fingerprints, i));
        fingerprintsArray.push_back(
                {
                        .hash = tgvoip::jni::JavaStringToStdString(env, fingerprintObject.getStringField("hash")),
                        .setup = tgvoip::jni::JavaStringToStdString(env, fingerprintObject.getStringField("setup")),
                        .fingerprint = tgvoip::jni::JavaStringToStdString(env, fingerprintObject.getStringField("fingerprint"))
                });
    }
    size = env->GetArrayLength(candidates);
    for (int i = 0; i < size; i++) {
        JavaObject candidateObject(env, env->GetObjectArrayElement(candidates, i));
        candidatesArray.push_back(
                {
                        .port = tgvoip::jni::JavaStringToStdString(env, candidateObject.getStringField("port")),
                        .protocol = tgvoip::jni::JavaStringToStdString(env, candidateObject.getStringField("protocol")),
                        .network = tgvoip::jni::JavaStringToStdString(env, candidateObject.getStringField("network")),
                        .generation = tgvoip::jni::JavaStringToStdString(env, candidateObject.getStringField("generation")),
                        .id = tgvoip::jni::JavaStringToStdString(env, candidateObject.getStringField("id")),
                        .component = tgvoip::jni::JavaStringToStdString(env, candidateObject.getStringField("component")),
                        .foundation = tgvoip::jni::JavaStringToStdString(env, candidateObject.getStringField("foundation")),
                        .priority = tgvoip::jni::JavaStringToStdString(env, candidateObject.getStringField("priority")),
                        .ip = tgvoip::jni::JavaStringToStdString(env, candidateObject.getStringField("ip")),
                        .type = tgvoip::jni::JavaStringToStdString(env, candidateObject.getStringField("type")),
                        .tcpType = tgvoip::jni::JavaStringToStdString(env, candidateObject.getStringField("tcpType")),
                        .relAddr = tgvoip::jni::JavaStringToStdString(env, candidateObject.getStringField("relAddr")),
                        .relPort = tgvoip::jni::JavaStringToStdString(env, candidateObject.getStringField("relPort")),
                });
    }

    instance->groupNativeInstance->setJoinResponsePayload(
            {
                    .ufrag = tgvoip::jni::JavaStringToStdString(env, ufrag),
                    .pwd = tgvoip::jni::JavaStringToStdString(env, pwd),
                    .fingerprints = fingerprintsArray,
                    .candidates = candidatesArray,
            });
}

JNIEXPORT void JNICALL Java_org_telegram_messenger_voip_NativeInstance_removeSsrcs(JNIEnv *env, jobject obj, jintArray ssrcs) {
    InstanceHolder *instance = getInstanceHolder(env, obj);
    if (instance->groupNativeInstance == nullptr) {
        return;
    }
    jsize size = env->GetArrayLength(ssrcs);

    std::vector<uint32_t> ssrcsArray;
    ssrcsArray.resize(size);
    for (int i = 0; i < size; i++) {
        env->GetIntArrayRegion(ssrcs, 0, size, reinterpret_cast<jint *>(ssrcsArray.data()));
    }
    instance->groupNativeInstance->removeSsrcs(ssrcsArray);
}

JNIEXPORT jlong JNICALL Java_org_telegram_messenger_voip_NativeInstance_makeNativeInstance(JNIEnv *env, jclass clazz, jstring version, jobject instanceObj, jobject config, jstring persistentStateFilePath, jobjectArray endpoints, jobject proxyClass, jint networkType, jobject encryptionKey, jobject remoteSink, jlong videoCapturer, jfloat aspectRatio) {
    initWebRTC(env);

    JavaObject configObject(env, config);
    JavaObject encryptionKeyObject(env, encryptionKey);
    std::string v = tgvoip::jni::JavaStringToStdString(env, version);

    jbyteArray valueByteArray = encryptionKeyObject.getByteArrayField("value");
    auto *valueBytes = (uint8_t *) env->GetByteArrayElements(valueByteArray, nullptr);
    auto encryptionKeyValue = std::make_shared<std::array<uint8_t, 256>>();
    memcpy(encryptionKeyValue->data(), valueBytes, 256);
    env->ReleaseByteArrayElements(valueByteArray, (jbyte *) valueBytes, JNI_ABORT);

    std::shared_ptr<VideoCaptureInterface> videoCapture = videoCapturer ? std::shared_ptr<VideoCaptureInterface>(reinterpret_cast<VideoCaptureInterface *>(videoCapturer)) : nullptr;

    std::shared_ptr<PlatformContext> platformContext;
    if (videoCapture) {
        platformContext = videoCapture->getPlatformContext();
        ((AndroidContext *) platformContext.get())->setJavaInstance(env, instanceObj);
    } else {
        platformContext = std::make_shared<AndroidContext>(env, instanceObj);
    }

    Descriptor descriptor = {
            .config = Config{
                    .initializationTimeout = configObject.getDoubleField("initializationTimeout"),
                    .receiveTimeout = configObject.getDoubleField("receiveTimeout"),
                    .dataSaving = parseDataSaving(env, configObject.getIntField("dataSaving")),
                    .enableP2P = configObject.getBooleanField("enableP2p") == JNI_TRUE,
                    .enableStunMarking = configObject.getBooleanField("enableSm") == JNI_TRUE,
                    .enableAEC = configObject.getBooleanField("enableAec") == JNI_TRUE,
                    .enableNS = configObject.getBooleanField("enableNs") == JNI_TRUE,
                    .enableAGC = configObject.getBooleanField("enableAgc") == JNI_TRUE,
                    .enableVolumeControl = true,
                    .logPath = tgvoip::jni::JavaStringToStdString(env, configObject.getStringField("logPath")),
                    .statsLogPath = tgvoip::jni::JavaStringToStdString(env, configObject.getStringField("statsLogPath")),
                    .maxApiLayer = configObject.getIntField("maxApiLayer"),
                    .enableHighBitrateVideo = true,
                    .preferredVideoCodecs = {cricket::kVp9CodecName}
            },
            .encryptionKey = EncryptionKey(
                    std::move(encryptionKeyValue),
                    encryptionKeyObject.getBooleanField("isOutgoing") == JNI_TRUE),
            .videoCapture =  videoCapture,
            .stateUpdated = [platformContext](State state) {
                jint javaState = asJavaState(state);
                jobject globalRef = ((AndroidContext *) platformContext.get())->getJavaInstance();
                tgvoip::jni::DoWithJNI([globalRef, javaState](JNIEnv *env) {
                    env->CallVoidMethod(globalRef, env->GetMethodID(NativeInstanceClass, "onStateUpdated", "(I)V"), javaState);
                });
            },
            .signalBarsUpdated = [platformContext](int count) {
                jobject globalRef = ((AndroidContext *) platformContext.get())->getJavaInstance();
                tgvoip::jni::DoWithJNI([globalRef, count](JNIEnv *env) {
                    env->CallVoidMethod(globalRef, env->GetMethodID(NativeInstanceClass, "onSignalBarsUpdated", "(I)V"), count);
                });
            },
            .audioLevelUpdated = [platformContext](float level) {
                tgvoip::jni::DoWithJNI([platformContext, level](JNIEnv *env) {
                    jintArray intArray = nullptr;
                    jfloatArray floatArray = env->NewFloatArray(1);
                    jbooleanArray boolArray = nullptr;

                    jfloat floatFill[1];
                    floatFill[0] = level;
                    env->SetFloatArrayRegion(floatArray, 0, 1, floatFill);

                    jobject globalRef = ((AndroidContext *) platformContext.get())->getJavaInstance();
                    env->CallVoidMethod(globalRef, env->GetMethodID(NativeInstanceClass, "onAudioLevelsUpdated", "([I[F[Z)V"), intArray, floatArray, boolArray);
                    env->DeleteLocalRef(floatArray);
                });
            },
            .remoteMediaStateUpdated = [platformContext](AudioState audioState, VideoState videoState) {
                jobject globalRef = ((AndroidContext *) platformContext.get())->getJavaInstance();
                tgvoip::jni::DoWithJNI([globalRef, audioState, videoState](JNIEnv *env) {
                    env->CallVoidMethod(globalRef, env->GetMethodID(NativeInstanceClass, "onRemoteMediaStateUpdated", "(II)V"), (jint) audioState, (jint )videoState);
                });
            },
            .signalingDataEmitted = [platformContext](const std::vector<uint8_t> &data) {
                jobject globalRef = ((AndroidContext *) platformContext.get())->getJavaInstance();
                tgvoip::jni::DoWithJNI([globalRef, data](JNIEnv *env) {
                    jbyteArray arr = copyVectorToJavaByteArray(env, data);
                    env->CallVoidMethod(globalRef, env->GetMethodID(NativeInstanceClass, "onSignalingData", "([B)V"), arr);
                    env->DeleteLocalRef(arr);
                });
            },
            .platformContext = platformContext,
    };

    for (int i = 0, size = env->GetArrayLength(endpoints); i < size; i++) {
        JavaObject endpointObject(env, env->GetObjectArrayElement(endpoints, i));
        bool isRtc = endpointObject.getBooleanField("isRtc");
        if (isRtc) {
            RtcServer rtcServer;
            rtcServer.host = tgvoip::jni::JavaStringToStdString(env, endpointObject.getStringField("ipv4"));
            rtcServer.port = static_cast<uint16_t>(endpointObject.getIntField("port"));
            rtcServer.login = tgvoip::jni::JavaStringToStdString(env, endpointObject.getStringField("username"));
            rtcServer.password = tgvoip::jni::JavaStringToStdString(env, endpointObject.getStringField("password"));
            rtcServer.isTurn = endpointObject.getBooleanField("turn");
            descriptor.rtcServers.push_back(std::move(rtcServer));
        } else {
            Endpoint endpoint;
            endpoint.endpointId = endpointObject.getLongField("id");
            endpoint.host = EndpointHost{tgvoip::jni::JavaStringToStdString(env, endpointObject.getStringField("ipv4")), tgvoip::jni::JavaStringToStdString(env, endpointObject.getStringField("ipv6"))};
            endpoint.port = static_cast<uint16_t>(endpointObject.getIntField("port"));
            endpoint.type = parseEndpointType(env, endpointObject.getIntField("type"));
            jbyteArray peerTag = endpointObject.getByteArrayField("peerTag");
            if (peerTag && env->GetArrayLength(peerTag)) {
                jbyte *peerTagBytes = env->GetByteArrayElements(peerTag, nullptr);
                memcpy(endpoint.peerTag, peerTagBytes, 16);
                env->ReleaseByteArrayElements(peerTag, peerTagBytes, JNI_ABORT);
            }
            descriptor.endpoints.push_back(std::move(endpoint));
        }
    }

    if (!env->IsSameObject(proxyClass, nullptr)) {
        JavaObject proxyObject(env, proxyClass);
        descriptor.proxy = std::unique_ptr<Proxy>(new Proxy);
        descriptor.proxy->host = tgvoip::jni::JavaStringToStdString(env, proxyObject.getStringField("host"));
        descriptor.proxy->port = static_cast<uint16_t>(proxyObject.getIntField("port"));
        descriptor.proxy->login = tgvoip::jni::JavaStringToStdString(env, proxyObject.getStringField("login"));
        descriptor.proxy->password = tgvoip::jni::JavaStringToStdString(env, proxyObject.getStringField("password"));
    }

    readPersistentState(tgvoip::jni::JavaStringToStdString(env, persistentStateFilePath).c_str(), descriptor.persistentState);

    auto *holder = new InstanceHolder;
    holder->nativeInstance = tgcalls::Meta::Create(v, std::move(descriptor));
    holder->_videoCapture = videoCapture;
    holder->_platformContext = platformContext;
    holder->nativeInstance->setIncomingVideoOutput(webrtc::JavaToNativeVideoSink(env, remoteSink));
    holder->nativeInstance->setNetworkType(parseNetworkType(networkType));
    holder->nativeInstance->setRequestedVideoAspect(aspectRatio);
    return reinterpret_cast<jlong>(holder);
}

JNIEXPORT void JNICALL Java_org_telegram_messenger_voip_NativeInstance_setGlobalServerConfig(JNIEnv *env, jobject obj, jstring serverConfigJson) {
    SetLegacyGlobalServerConfig(tgvoip::jni::JavaStringToStdString(env, serverConfigJson));
}

JNIEXPORT jstring JNICALL Java_org_telegram_messenger_voip_NativeInstance_getVersion(JNIEnv *env, jobject obj) {
    return env->NewStringUTF(tgvoip::VoIPController::GetVersion());
}

JNIEXPORT void JNICALL Java_org_telegram_messenger_voip_NativeInstance_setBufferSize(JNIEnv *env, jobject obj, jint size) {
    tgvoip::audio::AudioOutputOpenSLES::nativeBufferSize = (unsigned int) size;
    tgvoip::audio::AudioInputOpenSLES::nativeBufferSize = (unsigned int) size;
}

JNIEXPORT void JNICALL Java_org_telegram_messenger_voip_NativeInstance_setNetworkType(JNIEnv *env, jobject obj, jint networkType) {
    InstanceHolder *instance = getInstanceHolder(env, obj);
    if (instance->nativeInstance == nullptr) {
        return;
    }
    instance->nativeInstance->setNetworkType(parseNetworkType(networkType));
}

JNIEXPORT void JNICALL Java_org_telegram_messenger_voip_NativeInstance_setMuteMicrophone(JNIEnv *env, jobject obj, jboolean muteMicrophone) {
    InstanceHolder *instance = getInstanceHolder(env, obj);
    if (instance->nativeInstance != nullptr) {
        instance->nativeInstance->setMuteMicrophone(muteMicrophone);
    } else if (instance->groupNativeInstance != nullptr) {
        instance->groupNativeInstance->setIsMuted(muteMicrophone);
    }
}

JNIEXPORT void JNICALL Java_org_telegram_messenger_voip_NativeInstance_setVolume(JNIEnv *env, jobject obj, jint ssrc, jdouble volume) {
    InstanceHolder *instance = getInstanceHolder(env, obj);
    if (instance->groupNativeInstance != nullptr) {
        instance->groupNativeInstance->setVolume(ssrc, volume);
    }
}

JNIEXPORT void JNICALL Java_org_telegram_messenger_voip_NativeInstance_setAudioOutputGainControlEnabled(JNIEnv *env, jobject obj, jboolean enabled) {
    InstanceHolder *instance = getInstanceHolder(env, obj);
    if (instance->nativeInstance == nullptr) {
        return;
    }
    instance->nativeInstance->setAudioOutputGainControlEnabled(enabled);
}

JNIEXPORT void JNICALL Java_org_telegram_messenger_voip_NativeInstance_setEchoCancellationStrength(JNIEnv *env, jobject obj, jint strength) {
    InstanceHolder *instance = getInstanceHolder(env, obj);
    if (instance->nativeInstance == nullptr) {
        return;
    }
    instance->nativeInstance->setEchoCancellationStrength(strength);
}

JNIEXPORT jstring JNICALL Java_org_telegram_messenger_voip_NativeInstance_getLastError(JNIEnv *env, jobject obj) {
    InstanceHolder *instance = getInstanceHolder(env, obj);
    if (instance->nativeInstance == nullptr) {
        return nullptr;
    }
    return env->NewStringUTF(instance->nativeInstance->getLastError().c_str());
}

JNIEXPORT jstring JNICALL Java_org_telegram_messenger_voip_NativeInstance_getDebugInfo(JNIEnv *env, jobject obj) {
    InstanceHolder *instance = getInstanceHolder(env, obj);
    if (instance->nativeInstance == nullptr) {
        return nullptr;
    }
    return env->NewStringUTF(instance->nativeInstance->getDebugInfo().c_str());
}

JNIEXPORT jlong JNICALL Java_org_telegram_messenger_voip_NativeInstance_getPreferredRelayId(JNIEnv *env, jobject obj) {
    InstanceHolder *instance = getInstanceHolder(env, obj);
    if (instance->nativeInstance == nullptr) {
        return 0;
    }
    return instance->nativeInstance->getPreferredRelayId();
}

JNIEXPORT jobject JNICALL Java_org_telegram_messenger_voip_NativeInstance_getTrafficStats(JNIEnv *env, jobject obj) {
    InstanceHolder *instance = getInstanceHolder(env, obj);
    if (instance->nativeInstance == nullptr) {
        return nullptr;
    }
    return asJavaTrafficStats(env, instance->nativeInstance->getTrafficStats());
}

JNIEXPORT jbyteArray JNICALL Java_org_telegram_messenger_voip_NativeInstance_getPersistentState(JNIEnv *env, jobject obj) {
    InstanceHolder *instance = getInstanceHolder(env, obj);
    if (instance->nativeInstance == nullptr) {
        return nullptr;
    }
    return copyVectorToJavaByteArray(env, instance->nativeInstance->getPersistentState().value);
}

JNIEXPORT void JNICALL Java_org_telegram_messenger_voip_NativeInstance_stopNative(JNIEnv *env, jobject obj) {
    InstanceHolder *instance = getInstanceHolder(env, obj);
    if (instance->nativeInstance == nullptr) {
        return;
    }
    instance->nativeInstance->stop([instance](FinalState finalState) {
        JNIEnv *env = webrtc::AttachCurrentThreadIfNeeded();
        jobject globalRef = ((AndroidContext *) instance->_platformContext.get())->getJavaInstance();
        const std::string &path = tgvoip::jni::JavaStringToStdString(env, JavaObject(env, globalRef).getStringField("persistentStateFilePath"));
        savePersistentState(path.c_str(), finalState.persistentState);
        env->CallVoidMethod(globalRef, env->GetMethodID(NativeInstanceClass, "onStop", "(Lorg/telegram/messenger/voip/Instance$FinalState;)V"), asJavaFinalState(env, finalState));
        delete instance;
    });
}

JNIEXPORT void JNICALL Java_org_telegram_messenger_voip_NativeInstance_stopGroupNative(JNIEnv *env, jobject obj) {
    InstanceHolder *instance = getInstanceHolder(env, obj);
    if (instance->groupNativeInstance == nullptr) {
        return;
    }
    instance->groupNativeInstance->stop();
    instance->groupNativeInstance.reset();
    delete instance;
}

JNIEXPORT jlong JNICALL Java_org_telegram_messenger_voip_NativeInstance_createVideoCapturer(JNIEnv *env, jclass clazz, jobject localSink, jboolean front) {
    initWebRTC(env);
    std::unique_ptr<VideoCaptureInterface> capture = tgcalls::VideoCaptureInterface::Create(front ? "front" : "back", std::make_shared<AndroidContext>(env, nullptr));
    capture->setOutput(webrtc::JavaToNativeVideoSink(env, localSink));
    capture->setState(VideoState::Active);
    return reinterpret_cast<intptr_t>(capture.release());
}

JNIEXPORT void JNICALL Java_org_telegram_messenger_voip_NativeInstance_destroyVideoCapturer(JNIEnv *env, jclass clazz, jlong videoCapturer) {
    VideoCaptureInterface *capturer = reinterpret_cast<VideoCaptureInterface *>(videoCapturer);
    delete capturer;
}

JNIEXPORT void JNICALL Java_org_telegram_messenger_voip_NativeInstance_switchCameraCapturer(JNIEnv *env, jclass clazz, jlong videoCapturer, jboolean front) {
    VideoCaptureInterface *capturer = reinterpret_cast<VideoCaptureInterface *>(videoCapturer);
    capturer->switchToDevice(front ? "front" : "back");
}

JNIEXPORT void JNICALL Java_org_telegram_messenger_voip_NativeInstance_setVideoStateCapturer(JNIEnv *env, jclass clazz, jlong videoCapturer, jint videoState) {
    VideoCaptureInterface *capturer = reinterpret_cast<VideoCaptureInterface *>(videoCapturer);
    capturer->setState(static_cast<VideoState>(videoState));
}

JNIEXPORT void JNICALL Java_org_telegram_messenger_voip_NativeInstance_switchCamera(JNIEnv *env, jobject obj, jboolean front) {
    InstanceHolder *instance = getInstanceHolder(env, obj);
    if (instance->nativeInstance == nullptr) {
        return;
    }
    if (instance->_videoCapture == nullptr) {
        return;
    }
    instance->_videoCapture->switchToDevice(front ? "front" : "back");
}

JNIEXPORT void Java_org_telegram_messenger_voip_NativeInstance_setVideoState(JNIEnv *env, jobject obj, jint state) {
    InstanceHolder *instance = getInstanceHolder(env, obj);
    if (instance->nativeInstance == nullptr) {
        return;
    }
    if (instance->_videoCapture == nullptr) {
        return;
    }
    instance->_videoCapture->setState(static_cast<VideoState>(state));
}

JNIEXPORT void JNICALL Java_org_telegram_messenger_voip_NativeInstance_setupOutgoingVideo(JNIEnv *env, jobject obj, jobject localSink, jboolean front) {
    InstanceHolder *instance = getInstanceHolder(env, obj);
    if (instance->nativeInstance == nullptr) {
        return;
    }
    if (instance->_videoCapture) {
        return;
    }
    instance->_videoCapture = tgcalls::VideoCaptureInterface::Create(front ? "front" : "back", instance->_platformContext);
    instance->_videoCapture->setOutput(webrtc::JavaToNativeVideoSink(env, localSink));
    instance->_videoCapture->setState(VideoState::Active);
    instance->nativeInstance->setVideoCapture(instance->_videoCapture);
}

JNIEXPORT void JNICALL Java_org_telegram_messenger_voip_NativeInstance_onSignalingDataReceive(JNIEnv *env, jobject obj, jbyteArray value) {
    InstanceHolder *instance = getInstanceHolder(env, obj);
    if (instance->nativeInstance == nullptr) {
        return;
    }

    auto *valueBytes = (uint8_t *) env->GetByteArrayElements(value, nullptr);
    const size_t size = env->GetArrayLength(value);
    auto array = std::vector<uint8_t>(size);
    memcpy(&array[0], valueBytes, size);
    instance->nativeInstance->receiveSignalingData(std::move(array));
    env->ReleaseByteArrayElements(value, (jbyte *) valueBytes, JNI_ABORT);
}

}