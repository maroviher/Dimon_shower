package com.example.rmoschenski.dimon_shower;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CaptureRequest;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;


public class ConnectionLoop implements Runnable {
    private Thread m_thread;

    ServerSocketChannel mServerSocketChannel;
    SocketChannel mSocketChannel;
    InputStream mInputStream;
    MainActivity mMainActivity;

    AudioOutgoing mAudioOutgoing = new AudioOutgoing();
    SendDataThread mSendDataThread = new SendDataThread();
    AudioIncoming mAudioIncoming = new AudioIncoming();
    private final byte frame_type_audio = 4, frame_type_audioConfig = 6;

    private boolean mbConnectThreadShouldRun;

    ConnectionLoop(MainActivity mainActivity) {
        mMainActivity = mainActivity;
    }

    public boolean IsThreadRunning() {
        return !(m_thread == null);
    }

    public void StartConnectionListenerLoop() {
        mbConnectThreadShouldRun = true;
        mSendDataThread = new SendDataThread();
        m_thread = new Thread(this);
        m_thread.start();
    }

    public void StopConnectionListenerLoop() {
        mAudioIncoming.Stop();
        mAudioOutgoing.Stop();
        mMainActivity.mVideoOutgoing.Stop();
        mSendDataThread.Stop();
        mSendDataThread = null;

        mbConnectThreadShouldRun = false;
        if (mServerSocketChannel != null) {
            try {
                mServerSocketChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        if (mSocketChannel != null) {
            try {
                mSocketChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        try {
            if(m_thread != null) {
                mAudioIncoming.mFifoAudioIn.put(-2);
                m_thread.join();
                m_thread = null;
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
/*
            NotifyConnectionLostOrExit();

            try {
                if(m_thread != null) {
                    m_thread.join();
                    m_thread = null;
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }*/
    }
/*
        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec mediaCodec, int i) {
            if(mAudioCodec == mediaCodec)
                AddInputAudioBufferToQueue(i);
        }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec mediaCodec, int i, @NonNull MediaCodec.BufferInfo bufferInfo) {
            if(!m_bConnected) {
                try {
                    mediaCodec.releaseOutputBuffer(i, true);
                } catch (IllegalStateException ex) {
                    Log.d("------", "IllegalStateException in mediaCodec.releaseOutputBuffer");
                }
                return;
            }
            boolean bConn_lost = false;
            if(m_bAfterReconnect)
            {
                m_bAfterReconnect = false;

                try {
                    AfterReconnect();
                    AudioAfterReconnect();
                } catch (IOException e) {
                    if (bConn_lost) {
                        m_bConnected = false;

                        CleanUPSockets();
                        NotifyConnectionLostOrExit();
                    }
                    mediaCodec.releaseOutputBuffer(i, true);
                    return;
                }
            }

            try {
                ByteBuffer buf = mediaCodec.getOutputBuffer(i);
                byte[] bytes = IntToByteArr(bufferInfo.size);
                ByteBuffer bbufLen = ByteBuffer.wrap(bytes);
                byte[] DataType = new byte[1];
                if (mAudioCodec == mediaCodec) {
                    DataType[0] = frame_type_audio;
                }
                else if (mVideoCodecEncoder == mediaCodec) {
                    DataType[0] = frame_type_video;
                }
                switch (bufferInfo.flags) {
                    case 0://normal frame
                        break;
                    case 1://BUFFER_FLAG_KEY_FRAME
                        break;
                    case 2://BUFFER_FLAG_CODEC_CONFIG
                        ByteBuffer bb = ByteBuffer.allocate(buf.limit());
                        buf.rewind();
                        bb.put(buf);
                        buf.rewind();
                        bb.flip();
                        if (mAudioCodec == mediaCodec) {
                            m_bbufConfigAudio = bb;
                            DataType[0] = frame_type_audio;
                        }
                        else if (mVideoCodecEncoder == mediaCodec) {
                            m_bbufConfigVideo = bb;
                            DataType[0] = frame_type_video;
                        }
                        break;
                }
                //Log.d("--------", "bufferInfo.size=" + bufferInfo.size + " DataType[0]=" + DataType[0]);

                int iSent = 0;
                //send TLV
                iSent += mSocketChannel.write(ByteBuffer.wrap(DataType));
                iSent += mSocketChannel.write(bbufLen);
                iSent += mSocketChannel.write(buf);
                if (iSent != 1 + 4 + bufferInfo.size) {
                    Log.d("--------", "overflow");
                    bConn_lost = true;
                }
            } catch (IOException e) {
                Log.d("--------", "IOException");
                bConn_lost = true;
            }

            if (bConn_lost) {
                m_bConnected = false;
                //CleanUPSockets();
                //NotifyConnectionLostOrExit();
            }
            mediaCodec.releaseOutputBuffer(i, true);
        }

        @Override
        public void onError(@NonNull MediaCodec mediaCodec, @NonNull MediaCodec.CodecException e) {
        }

        @Override
        public void onOutputFormatChanged(@NonNull MediaCodec mediaCodec, @NonNull MediaFormat mediaFormat) {
        }*/

    SocketChannel ListenForHost() throws IOException {
        mServerSocketChannel = ServerSocketChannel.open();
        mServerSocketChannel.socket().setReuseAddress(true);
        mServerSocketChannel.socket().bind(new InetSocketAddress(5001));
        mMainActivity.setMessage("Waiting for a client on TCP port 5001");
        mSocketChannel = mServerSocketChannel.accept();
        mSocketChannel.socket().setSendBufferSize(2 * mMainActivity.GetBitrate() / 8);//send buffer as big as for 2 seconds
        mInputStream = mSocketChannel.socket().getInputStream();
        //mSocketChannel.socket().setSoTimeout(2000);
        //mSocketChannel.configureBlocking(false);
        mServerSocketChannel.close();
        mServerSocketChannel = null;
        mMainActivity.setMessage("Client connected!");
        return mSocketChannel;
    }

    private void CleanUPSockets() {
        if (mServerSocketChannel != null) {
            try {
                mServerSocketChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mServerSocketChannel = null;
        }

        if (mSocketChannel != null) {
            try {
                mSocketChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
        /*private void AudioAfterReconnect() throws IOException {
            //after reconnect first send a buffer with codec config saved before
            if (m_bbufConfigAudio != null) {
                byte[] bytes = IntToByteArr(m_bbufConfigAudio.limit());
                byte[] DataType = new byte[1];DataType[0] = frame_type_audio;
                mSocketChannel.write(ByteBuffer.wrap(DataType));
                mSocketChannel.write(ByteBuffer.wrap(bytes));
                mSocketChannel.write(m_bbufConfigAudio);
                m_bbufConfigAudio.rewind();
            }
        }

        private void NotifyConnectionLostOrExit() {
            lockConnected.lock();
            try {
                m_bConnectionLostOrExit = true;
                Log.d("------", "Notifying to reconnect");
                condVarExitOrDisconnect.signalAll();
            } finally {
                lockConnected.unlock();
            }
        }

        private void WaitForExitOrDisconnect() {
            Log.d("------", "Waiting for reconnect");
            lockConnected.lock();
            try {
                while(!m_bConnectionLostOrExit) {
                    try {
                        condVarExitOrDisconnect.await();
                    } catch (InterruptedException x) {}
                }
            } finally {
                m_bConnectionLostOrExit = false;
                lockConnected.unlock();
            }
            Log.d("------", "Notified to reconnect");
        }*/


    private boolean read_exact(byte buf[], int cnt) throws IOException {
        int iToRead = cnt;
        int offset = 0;
        int read;
        while(iToRead > 0)
        {
            read = mInputStream.read(buf, offset, iToRead);
            if(read < 1)
                return false;
            iToRead -= read;
            offset += read;
        }
        return true;
    }

    class TagAndLength {
        byte dataType;
        int iFrameLen;
    }

    TagAndLength ReadDataTypeAndFrameLength() throws Exception {
        byte[] buf = new byte[5];
        if (read_exact(buf, 5)) {
            TagAndLength tagAndLength = new TagAndLength();
            tagAndLength.iFrameLen =
                    (buf[1] & 0xFF) << 0  |
                    (buf[2] & 0xFF) << 8  |
                    (buf[3] & 0xFF) << 16 |
                    (buf[4] & 0xFF) << 24;

            if ((tagAndLength.iFrameLen > 1000000)||(tagAndLength.iFrameLen < 2)) {
                throw new Exception("ReadFrameLength iFrameLen=%d" + tagAndLength.iFrameLen);
            }
            tagAndLength.dataType = buf[0];
            return tagAndLength;
        }
        throw new Exception("read_exact read < 1");
    }

    public void run() {
        boolean bFirstConnect = true;

        CaptureRequest myCaptureRequest = mMainActivity.mRecordingRequestBuilder.build();

        while (mbConnectThreadShouldRun) {
            try {
                SocketChannel socketChannel = ListenForHost();
                mSendDataThread.SetSocketChannel(socketChannel);

                if(bFirstConnect) {
                    mAudioOutgoing.Start(mSendDataThread);
                    mMainActivity.mVideoOutgoing.Start(mSendDataThread);
                    mAudioIncoming.Start();
                    bFirstConnect = false;
                }
                else {
                    mAudioOutgoing.AfterReconnect();
                    mMainActivity.mVideoOutgoing.AfterReconnect();
                }

                try {
                    mMainActivity.mCameraCaptureSession.setRepeatingRequest(myCaptureRequest, null, null);
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }

                while(mbConnectThreadShouldRun) {
                    try {
                        TagAndLength tagAndLength;

                        try {
                            tagAndLength = ReadDataTypeAndFrameLength();
                        }catch(Exception ex) {
                            mMainActivity.setMessage(ex.getMessage());
                            break;
                        }

                        switch(tagAndLength.dataType) {
                            case frame_type_audio:
                            case frame_type_audioConfig:
                                break;
                            default:
                                Log.d("---------",
                                        "Unknown dataType=" + tagAndLength.dataType + " must be=" + frame_type_audio + "or" + frame_type_audioConfig);
                        }

                        //Log.d("---------", "iFrameLen=" + iFrameLen);
                        int inputBufferId;
                        try {
                            inputBufferId = mAudioIncoming.mFifoAudioIn.take();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                            break;
                        }
                        if (-2 == inputBufferId) {
                            mbConnectThreadShouldRun = false;
                            break;//stop the thread
                        }
                        try {
                            ByteBuffer inputBuffer = mAudioIncoming.mMediaCodecAudioDecoder.getInputBuffer(inputBufferId);
                            inputBuffer.limit(tagAndLength.iFrameLen);

                            while(inputBuffer.position() < tagAndLength.iFrameLen) {
                                mSocketChannel.read(inputBuffer);
                            }
                        }catch (IllegalStateException ex) {
                            mMainActivity.setMessage("IllegalStateException in AudioIncoming codec");
                            break;
                        }catch (IOException e) {
                            mMainActivity.setMessage(e.getMessage());
                            break;
                        }
                        mAudioIncoming.mMediaCodecAudioDecoder.queueInputBuffer(inputBufferId, 0, tagAndLength.iFrameLen, 0, 0);

                    } catch (Exception ex) {
                        mMainActivity.setMessage(ex.getMessage());
                        mMainActivity.mCameraCaptureSession.stopRepeating();
                        mAudioOutgoing.Pause();
                        break;
                    }
                }
            } catch (Exception e) {
                mMainActivity.setMessage(e.getMessage());
            }
        } //while (mbConnectThreadShouldRun) {

        //mAudioOutgoing.Stop();
        if(mMainActivity.mCameraCaptureSession != null)
            mMainActivity.mCameraCaptureSession.close();

        if(mMainActivity.mCameraDevice != null)
            mMainActivity.mCameraDevice.close();

        mMainActivity.setMessage("Idle...");
    }
}
