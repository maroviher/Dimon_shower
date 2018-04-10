package com.example.rmoschenski.dimon_shower;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.support.annotation.NonNull;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;


public class AudioIncoming {
    private static final String MIME_TYPE = "audio/mp4a-latm";

    MediaCodec mMediaCodecAudioDecoder;
    AudioTrack mAudioTrack;
    public BlockingQueue<Integer> mFifoAudioIn = new LinkedBlockingQueue<Integer>();

    public void Start(int iAECid, int iSampleRate) {
        mAudioTrack = new AudioTrack(AudioManager.MODE_IN_COMMUNICATION, iSampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, iSampleRate * 2 / 10 * 2,
                AudioTrack.MODE_STREAM, iAECid);

        if(null == (mMediaCodecAudioDecoder = InitAudioDecoder(iSampleRate)))
            return;

        mAudioTrack.play();
        mMediaCodecAudioDecoder.start();
    }

    public void Stop() {
        if(mAudioTrack != null) {
            mAudioTrack.stop();
            mAudioTrack.release();
            mAudioTrack = null;
        }

        if(mMediaCodecAudioDecoder != null) {
            mMediaCodecAudioDecoder.stop();
            mMediaCodecAudioDecoder.release();
            mMediaCodecAudioDecoder = null;
        }
    }

    private MediaCodec InitAudioDecoder(int iSampleRate) {
        MediaCodec mediaCodec = null;
        try {
            mediaCodec = MediaCodec.createDecoderByType(MIME_TYPE);
            MediaFormat mMediaFormat = new MediaFormat();
            mMediaFormat.setString(MediaFormat.KEY_MIME, MIME_TYPE);
            mMediaFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
            mMediaFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, iSampleRate);
            mediaCodec.setCallback(new MediaCodec.Callback() {
                @Override
                public void onInputBufferAvailable(@NonNull MediaCodec mediaCodec, int inputBufferId) {
                    //Log.d("onInputBufferAvailable", "" + inputBufferId);
                    try {
                        mFifoAudioIn.put(new Integer(inputBufferId));
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onOutputBufferAvailable(@NonNull MediaCodec mediaCodec, int inputBufferId, @NonNull MediaCodec.BufferInfo bufferInfo) {
                    ByteBuffer buf = mediaCodec.getOutputBuffer(inputBufferId);
                    //Log.d("onOutputBufferAvailable", "id=" + inputBufferId + " size=" +  buf.limit());
                    mAudioTrack.write(buf, buf.limit(), AudioTrack.WRITE_NON_BLOCKING);
                    mediaCodec.releaseOutputBuffer(inputBufferId, true);
                }

                @Override
                public void onError(@NonNull MediaCodec mediaCodec, @NonNull MediaCodec.CodecException e) {
                    Log.d("onError", "onError");
                    mediaCodec.reset();
                }

                @Override
                public void onOutputFormatChanged(@NonNull MediaCodec mediaCodec, @NonNull MediaFormat mediaFormat) {
                    Log.d("onOutputFormatChanged", "");
                }
            } );
            mediaCodec.configure(mMediaFormat, null, null, 0);

        }catch (Exception ex)
        {
            if (mediaCodec != null) {
                try {
                    mediaCodec.release();
                } catch (Exception ex1) {
                }
                mediaCodec = null;
            }
        }
        return mediaCodec;
    }
}