package com.example.rmoschenski.dimon_shower;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;


public class VideoOutgoing {
    I_CompressedBufferAvailable mI_CompressedBufferAvailable;
    int m_x, m_y, m_iBitrate;
    private MediaCodec mVideoCodecEncoder;
    String m_strCodec;
    ByteBuffer m_buf_config;

    public void Start(I_CompressedBufferAvailable iI_CompressedBufferAvailable) {
        mI_CompressedBufferAvailable = iI_CompressedBufferAvailable;
        if(mVideoCodecEncoder != null)
            mVideoCodecEncoder.start();
    }

    public void Stop() {
        if(mVideoCodecEncoder != null) {
            mVideoCodecEncoder.stop();
            mVideoCodecEncoder.release();
            mVideoCodecEncoder = null;
        }
    }

    public void AfterReconnect() throws IOException {
        //after reconnect first send a buffer with codec config saved before
        if (m_buf_config != null) {
            MyOutputBuffer myOutputBuffer = new MyOutputBuffer();
            myOutputBuffer.iAudioOrVideo = MyOutputBuffer.iBufType_VideoConfig;
            myOutputBuffer.mByteBufCodecConfig = m_buf_config;
            myOutputBuffer.mbufferID = 100;
            mI_CompressedBufferAvailable.CompressedBufferAvailable(myOutputBuffer);

            m_buf_config.rewind();

            //request a key-frame
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            mVideoCodecEncoder.setParameters(b);
        }
    }

    public Surface Init(int x, int y, int iBitrate, String strCodec) {
        m_x = x; m_y = y; m_iBitrate = iBitrate; m_strCodec = strCodec;
        MediaFormat format = MediaFormat.createVideoFormat(m_strCodec, m_x, m_y);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, m_iBitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        //format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 100);
        try {
            mVideoCodecEncoder = MediaCodec.createEncoderByType(m_strCodec);
        } catch (IOException ioe) {
            throw new IllegalStateException("failed to create video/avc encoder", ioe);
        }
        mVideoCodecEncoder.setCallback(new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(@NonNull MediaCodec mediaCodec, int inputBufferId) {
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
                //Log.d("onOutputBufferAvailable", ""+ inputBufferId);
                MyOutputBuffer myOutputBuffer = new MyOutputBuffer();
                myOutputBuffer.iAudioOrVideo = MyOutputBuffer.iBufType_Video;
                myOutputBuffer.mediaCodec = mediaCodec;
                myOutputBuffer.mbufferID = inputBufferId;
                //Log.d("", "i="+inputBufferId+" iFrameLen="+mediaCodec.getOutputBuffer(inputBufferId).limit()+" dataType="+myOutputBuffer.iAudioOrVideo);
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
        mVideoCodecEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        return mVideoCodecEncoder.createInputSurface();
    }
}
