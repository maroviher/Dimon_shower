package com.example.rmoschenski.dimon_shower;

import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class SendDataThread extends Thread implements I_CompressedBufferAvailable {
    private BlockingQueue<MyOutputBuffer> mFifoOutputBuffers = new LinkedBlockingQueue<>();
    private boolean m_bShouldRun = true;
    private SocketChannel mSocketChannel;

    SendDataThread() {
        m_bShouldRun = true;
        start();
    }

    @Override
    public void CompressedBufferAvailable(MyOutputBuffer myOutputBuffer) {
        try {
            mFifoOutputBuffers.put(myOutputBuffer);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void Stop() {
        m_bShouldRun = false;
        //unblock mFifoOutputBuffers.take() in another thread
        MyOutputBuffer stopMyOutputBuffer = new MyOutputBuffer();
        stopMyOutputBuffer.mbufferID = -2;
        try {
            mFifoOutputBuffers.put(stopMyOutputBuffer);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        try {
            join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void SetSocketChannel(SocketChannel socketChannel) {
        mSocketChannel = socketChannel;
    }

    public void run() {
        while(m_bShouldRun) {
            MyOutputBuffer myOutputBuffer = null;
            try {
                myOutputBuffer = mFifoOutputBuffers.take();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            if(myOutputBuffer.mbufferID < 0)
                continue;

            ByteBuffer bbufCodec = null;

            //send TLV
            try {
                switch (myOutputBuffer.iAudioOrVideo) {
                    case MyOutputBuffer.iBufType_Video:
                    case MyOutputBuffer.iBufType_Audio:
                        bbufCodec = myOutputBuffer.mediaCodec.getOutputBuffer(myOutputBuffer.mbufferID);
                        break;
                    case MyOutputBuffer.iBufType_AudioConfig:
                    case MyOutputBuffer.iBufType_VideoConfig:
                        bbufCodec = myOutputBuffer.mByteBufCodecConfig;
                        break;
                    default:
                        Log.d("1234", "unknown buffer type");
                }
                byte[] bufTagAndLen = new byte[5];
                bufTagAndLen[0] = myOutputBuffer.iAudioOrVideo;
                bufTagAndLen[4] = (byte) ((bbufCodec.limit() & 0xFF000000) >> 24);
                bufTagAndLen[3] = (byte) ((bbufCodec.limit() & 0x00FF0000) >> 16);
                bufTagAndLen[2] = (byte) ((bbufCodec.limit() & 0x0000FF00) >> 8);
                bufTagAndLen[1] = (byte) ((bbufCodec.limit() & 0x000000FF) >> 0);

                ByteBuffer bbuf = ByteBuffer.allocate(1 + 4 + bbufCodec.limit()).put(bufTagAndLen).put(bbufCodec);
                bbuf.rewind();
                mSocketChannel.write(bbuf);
            } catch (IOException e) {
                //e.printStackTrace();
            } catch (IllegalStateException ise) {
                ise.printStackTrace();
            }
            finally {
                try {
                    if(myOutputBuffer.mediaCodec != null) {
                        myOutputBuffer.mediaCodec.releaseOutputBuffer(myOutputBuffer.mbufferID, true);
                    }
                } catch (IllegalStateException ise) {
                    ise.printStackTrace();
                }
            }
        }
    }
}