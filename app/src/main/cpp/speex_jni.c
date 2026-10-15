// JNI bridge for dev.blue.codec.Speex: one narrowband encoder + decoder per handle.
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <speex/speex.h>

typedef struct {
    void *enc;
    void *dec;
    SpeexBits ebits;
    SpeexBits dbits;
    int frame; // samples per frame (160 at 8 kHz)
} Codec;

JNIEXPORT jlong JNICALL
Java_dev_blue_codec_Speex_nativeCreate(JNIEnv *env, jobject thiz, jint quality) {
    Codec *c = calloc(1, sizeof(Codec));
    if (!c) return 0;
    c->enc = speex_encoder_init(&speex_nb_mode);
    c->dec = speex_decoder_init(&speex_nb_mode);
    int q = quality, enh = 1;
    speex_encoder_ctl(c->enc, SPEEX_SET_QUALITY, &q);
    speex_encoder_ctl(c->enc, SPEEX_GET_FRAME_SIZE, &c->frame);
    speex_decoder_ctl(c->dec, SPEEX_SET_ENH, &enh);
    speex_bits_init(&c->ebits);
    speex_bits_init(&c->dbits);
    return (jlong) (intptr_t) c;
}

JNIEXPORT jint JNICALL
Java_dev_blue_codec_Speex_nativeFrameSize(JNIEnv *env, jobject thiz, jlong handle) {
    return ((Codec *) (intptr_t) handle)->frame;
}

/** Encodes one frame of PCM into `out`; returns the number of bytes written. */
JNIEXPORT jint JNICALL
Java_dev_blue_codec_Speex_nativeEncode(JNIEnv *env, jobject thiz, jlong handle, jshortArray pcm, jbyteArray out) {
    Codec *c = (Codec *) (intptr_t) handle;
    spx_int16_t in[c->frame];
    char buf[200];
    (*env)->GetShortArrayRegion(env, pcm, 0, c->frame, in);
    speex_bits_reset(&c->ebits);
    speex_encode_int(c->enc, in, &c->ebits);
    int cap = (*env)->GetArrayLength(env, out);
    int n = speex_bits_write(&c->ebits, buf, cap < (int) sizeof buf ? cap : (int) sizeof buf);
    (*env)->SetByteArrayRegion(env, out, 0, n, (jbyte *) buf);
    return n;
}

/** Decodes `length` bytes starting at `offset` into one frame of PCM; returns samples written or -1. */
JNIEXPORT jint JNICALL
Java_dev_blue_codec_Speex_nativeDecode(JNIEnv *env, jobject thiz, jlong handle, jbyteArray data, jint offset, jint length, jshortArray pcm) {
    Codec *c = (Codec *) (intptr_t) handle;
    char buf[200];
    spx_int16_t out[c->frame];
    if (length > (int) sizeof buf) return -1;
    (*env)->GetByteArrayRegion(env, data, offset, length, (jbyte *) buf);
    speex_bits_read_from(&c->dbits, buf, length);
    if (speex_decode_int(c->dec, &c->dbits, out) != 0) return -1;
    (*env)->SetShortArrayRegion(env, pcm, 0, c->frame, out);
    return c->frame;
}

JNIEXPORT void JNICALL
Java_dev_blue_codec_Speex_nativeDestroy(JNIEnv *env, jobject thiz, jlong handle) {
    Codec *c = (Codec *) (intptr_t) handle;
    if (!c) return;
    speex_encoder_destroy(c->enc);
    speex_decoder_destroy(c->dec);
    speex_bits_destroy(&c->ebits);
    speex_bits_destroy(&c->dbits);
    free(c);
}
