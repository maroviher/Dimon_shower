package com.example.rmoschenski.dimon_shower;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.support.annotation.NonNull;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class AudioOutgoing extends Thread {

    private static final String MIME_TYPE = "audio/mp4a-latm";
    private static final int SAMPLE_RATE = 44100;
    private static final int BIT_RATE = 32000;
    MediaCodec mAudioCodec;
    private BlockingQueue<Integer> mFifoAudioInputBuffers = new LinkedBlockingQueue<>();
    AudioRecord m_audio_recorder;
    boolean m_bAudioThreadShoudRun = false, m_bRunning = false;
    I_CompressedBufferAvailable mI_CompressedBufferAvailable;
    ByteBuffer m_buf_config;

    public void Start(I_CompressedBufferAvailable iI_CompressedBufferAvailable) {
        mI_CompressedBufferAvailable = iI_CompressedBufferAvailable;
        configureMediaCodecEncoderAudio();
        int min_buffer_size = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (AudioRecord.ERROR_BAD_VALUE == min_buffer_size)
            return;

        m_audio_recorder = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,       // MediaRecorder.AudioSource.VOICE_COMMUNICATION for echo cancellation
                SAMPLE_RATE,                         // sample rate, hz
                AudioFormat.CHANNEL_IN_MONO,                      // channels
                AudioFormat.ENCODING_PCM_16BIT,                        // audio format
                SAMPLE_RATE * 2 / 10);                     // buffer size 1/10 sec

        if (m_audio_recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            return;
        }

        mFifoAudioInputBuffers.clear();
        mAudioCodec.start();
        m_audio_recorder.startRecording();
        m_bAudioThreadShoudRun = true;
        start();
    }

    void Pause() {
        if (m_audio_recorder != null)
            m_audio_recorder.stop();
    }

    public void AfterReconnect() throws IOException {
        //after reconnect first send a buffer with codec config saved before
        if (m_buf_config != null) {
            MyOutputBuffer myOutputBuffer = new MyOutputBuffer();
            myOutputBuffer.iAudioOrVideo = MyOutputBuffer.iBufType_AudioConfig;
            myOutputBuffer.mByteBufCodecConfig = m_buf_config;
            myOutputBuffer.mbufferID = 100;

            mI_CompressedBufferAvailable.CompressedBufferAvailable(myOutputBuffer);

            m_buf_config.rewind();

            try {
                m_audio_recorder.startRecording();
            } catch (NullPointerException np) {
                int tt=1;
            }

        }
    }

    public void run() {
        m_bRunning = true;
        while (m_bAudioThreadShoudRun) {
            try {
                int inputBufferId = -1;
                try {
                    inputBufferId = mFifoAudioInputBuffers.take();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                if (-2 == inputBufferId)
                    break;//stop the thread

                if (inputBufferId < 0) {
                    Log.d("------", "GetInputAudioBufferFromQueue error");
                    break;
                }

                ByteBuffer inputBuffer = mAudioCodec.getInputBuffer(inputBufferId);
                int read_result = m_audio_recorder.read(inputBuffer, inputBuffer.limit());
                if (read_result == AudioRecord.ERROR_BAD_VALUE || read_result == AudioRecord.ERROR_INVALID_OPERATION) {
                    Log.e("AudioSoftwarePoller", "Read error");
                    break;
                }

                mAudioCodec.queueInputBuffer(inputBufferId, 0, read_result, 0, 0);
            } catch (IllegalStateException ex) {
                Log.d("IllegalStateException", "IllegalStateException");
            }
        }
        mFifoAudioInputBuffers.clear();
    }

    public void Stop() {
        if(!m_bRunning)
            return;

        m_bAudioThreadShoudRun = false;

        if (m_audio_recorder != null) {
            m_audio_recorder.stop();
            m_audio_recorder.release();
            m_audio_recorder = null;
        }

        //unblock GetInputAudioBufferFromQueue in another thread
        try {
            mFifoAudioInputBuffers.put(Integer.valueOf(-2));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        try {
            join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        if(mAudioCodec != null) {
            mAudioCodec.stop();
            mAudioCodec.release();
            mAudioCodec = null;
        }
    }

    private void configureMediaCodecEncoderAudio() {
        MediaFormat format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, 1);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_CHANNEL_MASK, AudioFormat.CHANNEL_IN_MONO);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
        try {
            mAudioCodec = MediaCodec.createEncoderByType(MIME_TYPE);
        } catch (IOException e) {
            e.printStackTrace();
        }
        mAudioCodec.setCallback(new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(@NonNull MediaCodec mediaCodec, int inputBufferId) {
                //Log.d("onInputBufferAvailable", "" + inputBufferId);
                try {
                    mFifoAudioInputBuffers.put(new Integer(inputBufferId));
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onOutputBufferAvailable(@NonNull MediaCodec mediaCodec, int inputBufferId, @NonNull MediaCodec.BufferInfo bufferInfo) {
                if(bufferInfo.flags == MediaCodec.BUFFER_FLAG_CODEC_CONFIG) {
                    ByteBuffer buf_config = mediaCodec.getOutputBuffer(inputBufferId);
                    m_buf_config = ByteBuffer.allocate(buf_config.limit());
                    buf_config.rewind();
                    m_buf_config.put(buf_config);
                    buf_config.rewind();
                    m_buf_config.flip();
                }
                MyOutputBuffer myOutputBuffer = new MyOutputBuffer();
                myOutputBuffer.iAudioOrVideo = MyOutputBuffer.iBufType_Audio;
                myOutputBuffer.mediaCodec = mediaCodec;
                myOutputBuffer.mbufferID = inputBufferId;
                mI_CompressedBufferAvailable.CompressedBufferAvailable(myOutputBuffer);
            }

            @Override
            public void onError(@NonNull MediaCodec mediaCodec, @NonNull MediaCodec.CodecException e) {
                Log.d("onError", "onError");
            }

            @Override
            public void onOutputFormatChanged(@NonNull MediaCodec mediaCodec, @NonNull MediaFormat mediaFormat) {
                Log.d("onOutputFormatChanged", "");
            }
        } );
        mAudioCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    }
}
