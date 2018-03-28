package com.example.rmoschenski.dimon_shower;

import android.media.MediaCodec;
import java.nio.ByteBuffer;


public class MyOutputBuffer {

    public final static byte iBufType_Video = 1;
    public final static byte iBufType_Audio = 4;
    public final static byte iBufType_VideoConfig = 5;
    public final static byte iBufType_AudioConfig = 6;

    public byte iAudioOrVideo;//Audio = 1, Video = 4, ://codec config = 5;
    public int mbufferID;
    MediaCodec mediaCodec;
    ByteBuffer mByteBufCodecConfig;
}
