package com.example.rmoschenski.dimon_shower;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.util.Log;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;


public class AudioIncoming {
    private static final String MIME_TYPE = "audio/mp4a-latm";

    MediaCodec mMediaCodecAudioDecoder;
    AudioTrack mAudioTrack;
    public BlockingQueue<Integer> mFifoAudioIn = new LinkedBlockingQueue<Integer>();
    int m_iPCM_DataWrote = 0, m_iSampleRate;
    TextView m_messageView;

    int iOldSampleRate = m_iSampleRate;

    int iLastPCM_DataWrote = 0;
    long l_ms = 0;
    void Adjust() {
        int i_l = m_iSampleRate/14, i_m = m_iSampleRate/12, i_r = m_iSampleRate/10, i_congestion = m_iSampleRate/4;
        double adjust = 1.003;
        int iNewSampleRate = 0;
        int m_iDelta = m_iPCM_DataWrote - mAudioTrack.getPlaybackHeadPosition();
        if(m_iDelta >= i_congestion) {//we have a real congestion, play it quick
            iNewSampleRate = (int) (m_iSampleRate + m_iSampleRate*(float)(m_iDelta)/m_iSampleRate);
        }
        else if(m_iDelta >= i_r) {//play quicker
            iNewSampleRate = (int) (m_iSampleRate * adjust);
        }
        else if((m_iDelta < i_m) && (m_iDelta >= i_l)) {//play with native speed
            iNewSampleRate = m_iSampleRate;
        }
        else if(m_iDelta < i_l) {//play slower
            iNewSampleRate = (int) (m_iSampleRate / adjust);
        }

        if(iOldSampleRate != iNewSampleRate) {
            mAudioTrack.setPlaybackRate(iNewSampleRate);
            iOldSampleRate = iNewSampleRate;
        }
        boolean bStatistics = true;
        if(bStatistics) {
            long time_delta_ms = System.currentTimeMillis() - l_ms;
            int i_msBetweenMeasure = 200;
            if (time_delta_ms >= i_msBetweenMeasure) {
                int pcm_delta = m_iPCM_DataWrote - iLastPCM_DataWrote;
                float iPCMperSec = pcm_delta * (float) 1000 / time_delta_ms;
                iLastPCM_DataWrote = m_iPCM_DataWrote;

                m_messageView.setText("PCM/s=" + (int) iPCMperSec + " underrun=" + mAudioTrack.getUnderrunCount() +
                        " delta=" + m_iDelta + " rate=" + mAudioTrack.getPlaybackRate());
                /*Log.d("asdf", "pcm_delta=" + pcm_delta + " iPCMperSec=" + iPCMperSec + " delta=" + m_iDelta +
                        " head=" + mAudioTrack.getPlaybackHeadPosition() + " rate=" + mAudioTrack.getPlaybackRate());*/
                l_ms += time_delta_ms;
            }
        }
    }

    public void Start(TextView messageView, int iAECid, int iSampleRate) {
        m_iSampleRate = iSampleRate;
        m_messageView = messageView;
        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
        mAudioTrack = new AudioTrack(AudioManager.MODE_IN_COMMUNICATION, iSampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, iSampleRate*2/2, //0.5 sec
                AudioTrack.MODE_STREAM, iAECid);
        mAudioTrack.play();

        if(null == (mMediaCodecAudioDecoder = InitAudioDecoder(iSampleRate)))
            return;

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
                int iOutputBufferCounter = 0;
                @Override
                public void onOutputBufferAvailable(@NonNull MediaCodec mediaCodec, int inputBufferId, @NonNull MediaCodec.BufferInfo bufferInfo) {
                    ByteBuffer buf = mediaCodec.getOutputBuffer(inputBufferId);
                    //Log.d("onOutputBufferAvailable", "id=" + inputBufferId + " size=" +  buf.limit());
                    int iBufSize = buf.limit();
                    int iWrote = mAudioTrack.write(buf, iBufSize, AudioTrack.WRITE_NON_BLOCKING);
                    m_iPCM_DataWrote += iWrote/2;
                    if(0 == (iOutputBufferCounter++ % 5))
                        Adjust();

                    mediaCodec.releaseOutputBuffer(inputBufferId, true);
                }

                @Override
                public void onError(@NonNull MediaCodec mediaCodec, @NonNull MediaCodec.CodecException e) {
                    Log.d("onError", "onError");
                    mediaCodec.reset();
                }

                @Override
                public void onOutputFormatChanged(@NonNull MediaCodec mediaCodec, @NonNull MediaFormat mediaFormat) {
                    //Log.d("onOutputFormatChanged", "");
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