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
    int m_iPCM_DataWrote = 0, m_iNotificationPeriod, m_iSampleRate;
    TextView m_messageView;

    public void Start(TextView messageView, int iAECid, int iSampleRate) {
        m_iSampleRate = iSampleRate;
        m_messageView = messageView;
        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
        mAudioTrack = new AudioTrack(AudioManager.MODE_IN_COMMUNICATION, iSampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT, iSampleRate*2,
                AudioTrack.MODE_STREAM, iAECid);

        m_iNotificationPeriod = iSampleRate/2;
        mAudioTrack.setPositionNotificationPeriod(m_iNotificationPeriod);//onPeriodicNotification gets called every 500ms
        mAudioTrack.setPlaybackPositionUpdateListener(new AudioTrack.OnPlaybackPositionUpdateListener() {
            @Override
            public void onMarkerReached(AudioTrack audioTrack) {}
long time = System.currentTimeMillis();
            int iOldSampleRate = m_iSampleRate;
            @Override
            public void onPeriodicNotification(AudioTrack audioTrack) {

                int i_l = m_iSampleRate/10, i_m = m_iSampleRate/8, i_r = m_iSampleRate/6, i_congestion = m_iSampleRate/4;
                double adjust = 1.003;
                int iNewSampleRate = 0;
                int m_iDelta = m_iPCM_DataWrote - mAudioTrack.getPlaybackHeadPosition();
                if(m_iDelta >= i_congestion) {//we have a real congestion, play it quick
                    iNewSampleRate = (int) (m_iSampleRate * 2);
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
                iNewSampleRate = (int) (m_iSampleRate * 2);
                if(iOldSampleRate != iNewSampleRate) {
                    mAudioTrack.setPlaybackRate(iNewSampleRate);
                    iOldSampleRate = iNewSampleRate;
                }
                double mytime1 = System.currentTimeMillis();
                m_messageView.setText((System.currentTimeMillis() - time) + "ms getUnderrunCount=" + mAudioTrack.getUnderrunCount() + " delta=" + m_iDelta + " rate="+mAudioTrack.getPlaybackRate());
                //Log.d("", "ms="+(mytime1 - mytime)+" getUnderrunCount=" + mAudioTrack.getUnderrunCount() + " delta=" + m_iDelta + " rate="+mAudioTrack.getPlaybackRate());
                time = System.currentTimeMillis();
            }
          });

        if(null == (mMediaCodecAudioDecoder = InitAudioDecoder(iSampleRate)))
            return;

        mAudioTrack.play();
        mMediaCodecAudioDecoder.start();
    }

    public void IncPcmDataCount(int i) {
        synchronized (this) {
            m_iPCM_DataWrote += i;
        }
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
int tt=0;long time_left, time_right;
boolean bInit = true;
                @Override
                public void onOutputBufferAvailable(@NonNull MediaCodec mediaCodec, int inputBufferId, @NonNull MediaCodec.BufferInfo bufferInfo) {
                    ByteBuffer buf = mediaCodec.getOutputBuffer(inputBufferId);
                    //Log.d("onOutputBufferAvailable", "id=" + inputBufferId + " size=" +  buf.limit());
                    int iBufSize = buf.limit();
                    tt+=iBufSize;

                    if(bInit) {
                        time_left = System.currentTimeMillis();
                        bInit = false;
                    }

                    long delta_ms = System.currentTimeMillis()-time_left;

                    if(delta_ms > 1000) {
                        Log.d("", "Bytes=" + tt + " in " + delta_ms + "ms");
                        tt = 0;
                        time_left = System.currentTimeMillis();
                    }

                    float[] buf_sin = new float[iBufSize/2];
                    byte[] buf_sin_byte = new byte[iBufSize];

                    double sin_max = Math.PI*2;

                    double step=(sin_max)/buf_sin.length;
                    int i = 0, ii=0;
                    for(double x = 0; x < (sin_max*0.999); x+=step) {
                        //str += ""+(double)(Math.sin(Math.toRadians(x))*1)+" ";
                        try {
                            buf_sin[i++] = (float)Math.sin(x);
                            int i_sample = (int)(32767*(1+buf_sin[i-1]));
                            buf_sin_byte[ii+0]   = (byte)(i_sample & 0xFF);
                            buf_sin_byte[ii+1] = (byte)((i_sample & 0xFF00)>>8);
                            ii+=2;

                            /*if(i%10== 0)
                                Log.d("", String.format("%05d=0x%02x%02x", i_sample, buf_sin_byte[ii-2], buf_sin_byte[ii-1]));*/
                        } catch (ArrayIndexOutOfBoundsException ssd) {
                            Log.d("", "");
                        }
                    }
                    mAudioTrack.write(buf_sin, 0, buf_sin.length, AudioTrack.WRITE_NON_BLOCKING);
                    //mAudioTrack.write(buf, iBufSize, AudioTrack.WRITE_NON_BLOCKING);
                    IncPcmDataCount(iBufSize/2);
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