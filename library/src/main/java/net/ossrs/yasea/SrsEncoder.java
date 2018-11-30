package net.ossrs.yasea;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by Leo Ma on 4/1/2016.
 */
public class SrsEncoder {
    private static final String TAG = "SrsEncoder";

    //视频类型
    public static final String VCODEC = "video/avc";
    //音频类型
    public static final String ACODEC = "audio/mp4a-latm";
    //压缩策略
    public static String x264Preset = "veryfast";
    //预览宽高
    public static int vPrevWidth = 640;
    public static int vPrevHeight = 360;
    //肖像宽高 -----------------用在哪里
    public static int vPortraitWidth = 360;
    public static int vPortraitHeight = 640;
    //横屏宽高
    public static int vLandscapeWidth = 640;
    public static int vLandscapeHeight = 360;
    //编码的视频数据宽高
    public static int vOutWidth = 360;   // Note: the stride of resolution must be set as 16x for hard encoding with some chip like MTK
    public static int vOutHeight = 640;  // Since Y component is quadruple size as U and V component, the stride must be set as 32x
    //码率 1.2M左右
    public static int vBitrate = 1200 * 1024;  // 1200 kbps
    //帧率
    public static final int VFPS = 24;
    //最大帧率 --------------------是这样吗？
    public static final int VGOP = 48;
    //采样率
    public static final int ASAMPLERATE = 44100;
    //声道格式
    public static int aChannelConfig = AudioFormat.CHANNEL_IN_STEREO;
    //比特率 --------------------------------这个跟码率是啥区别
    public static final int ABITRATE = 64 * 1024;  // 64 kbps

    //编码handler处理器
    private SrsEncodeHandler mHandler;

    //音视频混合器
    private SrsFlvMuxer flvMuxer;
    private SrsMp4Muxer mp4Muxer;

    //媒体编码信息对象、音、视频编码器对象
    private MediaCodecInfo vmci;
    private MediaCodec vencoder;
    private MediaCodec aencoder;

    //弱网标志类
    private boolean networkWeakTriggered = false;
    private boolean mCameraFaceFront = true;
    //是否使用软编 ，是否允许使用软编
    private boolean useSoftEncoder = false;
    private boolean canSoftEncode = false;

    //目前的时间基
    private long mPresentTimeUs;

    //视频编码格式
    private int mVideoColorFormat;

    //音视频编码器索引
    private int videoFlvTrack;
    private int videoMp4Track;
    private int audioFlvTrack;
    private int audioMp4Track;

    // Y, U (Cb) and V (Cr)
    // yuv420                     yuv yuv yuv yuv
    // yuv420p (planar)   yyyy*2 uu vv
    // yuv420sp(semi-planner)   yyyy*2 uv uv
    // I420 -> YUV420P   yyyy*2 uu vv
    // YV12 -> YUV420P   yyyy*2 vv uu
    // NV12 -> YUV420SP  yyyy*2 uv uv
    // NV21 -> YUV420SP  yyyy*2 vu vu
    // NV16 -> YUV422SP  yyyy uv uv
    // YUY2 -> YUV422SP  yuyv yuyv

    //构造方法 设置编码器Handler，视频编码格式
    public SrsEncoder(SrsEncodeHandler handler) {
        mHandler = handler;
        mVideoColorFormat = chooseVideoEncoder();
    }

    //设置Flv 一般用于rtmp
    public void setFlvMuxer(SrsFlvMuxer flvMuxer) {
        this.flvMuxer = flvMuxer;
    }

    //设置Mp4混合器， --------------------搞不清为啥要设置两个
    public void setMp4Muxer(SrsMp4Muxer mp4Muxer) {
        this.mp4Muxer = mp4Muxer;
    }

    //开始编码
    public boolean start() {
        //若mp4混合器、flv混合器为空，直接返回false
        if (flvMuxer == null || mp4Muxer == null) {
            return false;
        }

        //目前的时间
        // the referent PTS for video and audio encoder.
        mPresentTimeUs = System.nanoTime() / 1000;

        //注释解释：分辨率的步长必须设置为16x，对于使用MTK等芯片进行编码
        // Note: the stride of resolution must be set as 16x for hard encoding with some chip like MTK
        // 因为Y的分量是U和V分量的4倍，所以步幅必须设置为32x
        // Since Y component is quadruple size as U and V component, the stride must be set as 32x
        //如果不是软编码  并且 宽或者高 不是32的倍数，则可以抛异常 针对MTK
        if (!useSoftEncoder && (vOutWidth % 32 != 0 || vOutHeight % 32 != 0)) {
            if (vmci.getName().contains("MTK")) {
                //throw new AssertionError("MTK encoding revolution stride must be 32x");
            }
        }

        //native 设置编码分辨率----方法
        setEncoderResolution(vOutWidth, vOutHeight);
        //native 设置编码帧率
        setEncoderFps(VFPS);
        //native 设置编码 不知道是否是最大帧率 -------------------------------------表示疑问
        setEncoderGop(VGOP);
        // Unfortunately for some android phone, the output fps is less than 10 limited by the
        // capacity of poor cheap chips even with x264. So for the sake of quick appearance of
        // the first picture on the player, a spare lower GOP value is suggested. But note that
        // lower GOP will produce more I frames and therefore more streaming data flow.
        // setEncoderGop(15);
        //native 设置码率 默认 1200kbs 接近1.2M
        setEncoderBitrate(vBitrate);
        //native 编码策略
        setEncoderPreset(x264Preset);

        //native 采用软编
        if (useSoftEncoder) {
            //native 方法 打开软编
            canSoftEncode = openSoftEncoder();
            //如果软编打开失败 则返回
            if (!canSoftEncode) {
                return false;
            }
        }

        // 音频编码器
        // aencoder pcm to aac raw stream.
        // requires sdk level 16+, Android 4.1, 4.1.1, the JELLY_BEAN
        try {
//          ACODEC = "audio/mp4a-latm"; MediaCodec
            aencoder = MediaCodec.createEncoderByType(ACODEC);
        } catch (IOException e) {
            Log.e(TAG, "create aencoder failed.");
            e.printStackTrace();
            return false;
        }

        // setup the aencoder.
        // @see https://developer.android.com/reference/android/media/MediaCodec.html
        //根据声道格式 确定声道个数
        int ach = aChannelConfig == AudioFormat.CHANNEL_IN_STEREO ? 2 : 1;
        //通过媒体格式类 创建音频格式（音频格式，采样率，声道个数）
        MediaFormat audioFormat = MediaFormat.createAudioFormat(ACODEC, ASAMPLERATE, ach);
        // 设置比特率 ----------------------------比特率和码率是什么关系？
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, ABITRATE);
        //设置最大的输入大小
        audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0);
        //音频编码器 设置编码格式
        aencoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        // add the audio tracker to muxer.
        //添加编码格式到混合器中
        audioFlvTrack = flvMuxer.addTrack(audioFormat);
        audioMp4Track = mp4Muxer.addTrack(audioFormat);

        // vencoder yuv to 264 es stream.
        // requires sdk level 16+, Android 4.1, 4.1.1, the JELLY_BEAN
        try {
            //通过名称创建 视频编码器对象
            vencoder = MediaCodec.createByCodecName(vmci.getName());
        } catch (IOException e) {
            Log.e(TAG, "create vencoder failed.");
            e.printStackTrace();
            return false;
        }

        // setup the vencoder.
        // Note: landscape to portrait, 90 degree rotation, so we need to switch width and height in configuration
        //根据 VCODEC = "video/avc"; 视频格式创建视频格式对象，指定视频的宽高
        MediaFormat videoFormat = MediaFormat.createVideoFormat(VCODEC, vOutWidth, vOutHeight);
        //视频格式对象 设置视频编码器索引
        videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, mVideoColorFormat);
        //视频编码器格式对象设置最大输入大小
        videoFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0);
        //视频编码器格式对象设置码率
        videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, vBitrate);
        //视频编码器格式对象设置帧率
        videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, VFPS);
        //视频编码器格式对象 设置帧间间隔 --------------------------VGOP / VFPS
        videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VGOP / VFPS);
        //编码器对象配置编码格式，
        vencoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        // add the video tracker to muxer.
        //音视频混合器 添加 视频格式
        videoFlvTrack = flvMuxer.addTrack(videoFormat);
        videoMp4Track = mp4Muxer.addTrack(videoFormat);

        // start device and encoder.
        //开始视频硬件编码
        vencoder.start();
        //开启音频硬件编码
        aencoder.start();
        return true;
    }

    //停止编码
    public void stop() {
        //如果是软编
        if (useSoftEncoder) {
            //native 停止软编，将软编标志位改为false
            closeSoftEncoder();
            canSoftEncode = false;
        }

        //如果音频硬件编码器不为空，则停止音频硬件编码，并释放资源
        if (aencoder != null) {
            Log.i(TAG, "stop aencoder");
            aencoder.stop();
            aencoder.release();
            aencoder = null;
        }

        //如果视频硬件编码不为空，则停止视频硬件编码，并释放资源
        if (vencoder != null) {
            Log.i(TAG, "stop vencoder");
            vencoder.stop();
            vencoder.release();
            vencoder = null;
        }
    }

    //设置前置相机
    public void setCameraFrontFace() {
        mCameraFaceFront = true;
    }

    //设置后置相机
    public void setCameraBackFace() {
        mCameraFaceFront = false;
    }

    //切换为软编
    public void switchToSoftEncoder() {
        useSoftEncoder = true;
    }

    //切换为硬编
    public void switchToHardEncoder() {
        useSoftEncoder = false;
    }

    //是否是软编
    public boolean isSoftEncoder() {
        return useSoftEncoder;
    }

    //是否能硬编
    public boolean canHardEncode() {
        return vencoder != null;
    }

    //是否能软编
    public boolean canSoftEncode() {
        return canSoftEncode;
    }

    //是否可编码
    public boolean isEnabled() {
        return canHardEncode() || canSoftEncode();
    }

    //设置预览分辨率
    public void setPreviewResolution(int width, int height) {
        vPrevWidth = width;
        vPrevHeight = height;
    }

    //设置竖屏 视频分辨率，肖像宽高（有啥用？） 、横屏宽高（将宽高反过来赋值）
    public void setPortraitResolution(int width, int height) {
        vOutWidth = width;
        vOutHeight = height;
        vPortraitWidth = width;
        vPortraitHeight = height;
        vLandscapeWidth = height;
        vLandscapeHeight = width;
    }

    //设置横屏 视频分辨率 ，肖像宽高、竖屏宽高（将宽高反过来赋值）
    public void setLandscapeResolution(int width, int height) {
        vOutWidth = width;
        vOutHeight = height;
        vLandscapeWidth = width;
        vLandscapeHeight = height;
        vPortraitWidth = height;
        vPortraitHeight = width;
    }

    //设置硬编码率和编码策略
    public void setVideoHDMode() {
        vBitrate = 600 * 1024;  // 1200 kbps
        x264Preset = "veryfast";
    }

    //设置视频慢编码模式
    public void setVideoSmoothMode() {
        vBitrate = 500 * 1024;  // 500 kbps
        x264Preset = "superfast";
    }

    //设置预览宽度
    public int getPreviewWidth() {
        return vPrevWidth;
    }

    //设置预览高度
    public int getPreviewHeight() {
        return vPrevHeight;
    }

    //设置视频宽度
    public int getOutputWidth() {
        return vOutWidth;
    }

    //设置视频高度
    public int getOutputHeight() {
        return vOutHeight;
    }

    //设置屏幕方向，如果是横屏就视频宽度就取横屏宽高、如果是竖屏就取竖屏的宽高
    public void setScreenOrientation(int orientation) {
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            vOutWidth = vPortraitWidth;
            vOutHeight = vPortraitHeight;
        } else if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            vOutWidth = vLandscapeWidth;
            vOutHeight = vLandscapeHeight;
        }
        
        // Note: the stride of resolution must be set as 16x for hard encoding with some chip like MTK
        // Since Y component is quadruple size as U and V component, the stride must be set as 32x
        if (!useSoftEncoder && (vOutWidth % 32 != 0 || vOutHeight % 32 != 0)) {
            if (vmci.getName().contains("MTK")) {
                //throw new AssertionError("MTK encoding revolution stride must be 32x");
            }
        }

        //native 设置编码分辨率
        setEncoderResolution(vOutWidth, vOutHeight);
    }

    //处理像素帧数据
    private void onProcessedYuvFrame(byte[] yuvFrame, long pts) {
        //硬编码获取输入队列
        ByteBuffer[] inBuffers = vencoder.getInputBuffers();
        //硬编码获取输出队列
        ByteBuffer[] outBuffers = vencoder.getOutputBuffers();

        //媒体编码器 入队列。获取输入缓存的索引
        int inBufferIndex = vencoder.dequeueInputBuffer(-1);
        //如果输入缓存索引 不为零
        if (inBufferIndex >= 0) {
            //从输入缓存中拿到对应索引的 ByteBuffer
            ByteBuffer bb = inBuffers[inBufferIndex];
            //清除buff
            bb.clear();
            //将一帧数据添加到缓存中
            bb.put(yuvFrame, 0, yuvFrame.length);
            //将数据压入输入缓存。将帧数据 索引，帧数据长度，时间戳加入缓存队列
            vencoder.queueInputBuffer(inBufferIndex, 0, yuvFrame.length, pts, 0);
        }

        //开启循环编码
        for (; ; ) {
            MediaCodec.BufferInfo vebi = new MediaCodec.BufferInfo();
            //拿到媒体编码 缓存信息对象， 从输出编码队列中获取 媒体编码缓存信息
            int outBufferIndex = vencoder.dequeueOutputBuffer(vebi, 0);
            //如果编码输出索引 大于0
            if (outBufferIndex >= 0) {
                //拿到对应索引的 缓存对象
                ByteBuffer bb = outBuffers[outBufferIndex];
                //采用混合器 结合 媒体缓存信息对象 处理编码过的数据
                onEncodedAnnexbFrame(bb, vebi);
                //视频编码器释放 输出缓存，不重新渲染
                vencoder.releaseOutputBuffer(outBufferIndex, false);
            } else {
                break;
            }
        }
    }

    //软编码数据 ----------------------------- 软编数据用混合器处理 生成文件？？？
    private void onSoftEncodedData(byte[] es, long pts, boolean isKeyFrame) {
        //将像素 数据信息 添加到 buffer中
        ByteBuffer bb = ByteBuffer.wrap(es);
        //媒体缓存信息类
        MediaCodec.BufferInfo vebi = new MediaCodec.BufferInfo();
        //偏移、长度、帧数据时间戳
        vebi.offset = 0;
        vebi.size = es.length;
        vebi.presentationTimeUs = pts;
        //是否是关键帧
        vebi.flags = isKeyFrame ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
        onEncodedAnnexbFrame(bb, vebi);
    }

    // when got encoded h264 es stream.
    private void onEncodedAnnexbFrame(ByteBuffer es, MediaCodec.BufferInfo bi) {
        //混合器将编码输出写入
        mp4Muxer.writeSampleData(videoMp4Track, es.duplicate(), bi);
        flvMuxer.writeSampleData(videoFlvTrack, es, bi);
    }

    // when got encoded aac raw stream.
    private void onEncodedAacFrame(ByteBuffer es, MediaCodec.BufferInfo bi) {
        //混合器将音频数据写入
        mp4Muxer.writeSampleData(audioMp4Track, es.duplicate(), bi);
        flvMuxer.writeSampleData(audioFlvTrack, es, bi);
    }

    //处理获取到的 pcm 帧数据
    public void onGetPcmFrame(byte[] data, int size) {
        // Check video frame cache number to judge the networking situation.
        // Just cache GOP / FPS seconds data according to latency.
        //flv混合器 获取视频帧缓存 计数
        AtomicInteger videoFrameCacheNumber = flvMuxer.getVideoFrameCacheNumber();
        //如果 视频帧缓存计数 小于最大计数
        if (videoFrameCacheNumber != null && videoFrameCacheNumber.get() < VGOP) {
            //获取音频的输入缓存，输出缓存
            ByteBuffer[] inBuffers = aencoder.getInputBuffers();
            ByteBuffer[] outBuffers = aencoder.getOutputBuffers();

            //从输入队列中获取可用的 输入buffer
            int inBufferIndex = aencoder.dequeueInputBuffer(-1);
            //如果输入索引大于0，即有可用输入buff
            if (inBufferIndex >= 0) {
                //获取输入buff
                ByteBuffer bb = inBuffers[inBufferIndex];
                //清空输入buff
                bb.clear();
                //将pcm 数据添加到buff中
                bb.put(data, 0, size);
                //计算时间
                long pts = System.nanoTime() / 1000 - mPresentTimeUs;
                //将缓存数据 索引 和 时间戳压入输入队列
                aencoder.queueInputBuffer(inBufferIndex, 0, size, pts, 0);
            }

            //开始死循环 压缩数据
            for (; ; ) {
                //创建媒体缓存信息对象
                MediaCodec.BufferInfo aebi = new MediaCodec.BufferInfo();
                //获取可用的 输出buff 索引
                int outBufferIndex = aencoder.dequeueOutputBuffer(aebi, 0);
                //若有可用
                if (outBufferIndex >= 0) {
                    //找到buff
                    ByteBuffer bb = outBuffers[outBufferIndex];
                    //混合器处理音频数据
                    onEncodedAacFrame(bb, aebi);
                    //释放音频数据
                    aencoder.releaseOutputBuffer(outBufferIndex, false);
                } else {
                    break;
                }
            }
        }
    }

    //处理rgb 数据
    public void onGetRgbaFrame(byte[] data, int width, int height) {
        // Check video frame cache number to judge the networking situation.
        // Just cache GOP / FPS seconds data according to latency.
        //从混合器中获取视频帧缓存计数
        AtomicInteger videoFrameCacheNumber = flvMuxer.getVideoFrameCacheNumber();
        //如果计数小于最大计数
        if (videoFrameCacheNumber != null && videoFrameCacheNumber.get() < VGOP) {
            //计算时间戳
            long pts = System.nanoTime() / 1000 - mPresentTimeUs;
            //若允许软编码
            if (useSoftEncoder) {
                swRgbaFrame(data, width, height, pts);
            } else {
                //若采用硬编码 ，先软件转码
                byte[] processedData = hwRgbaFrame(data, width, height);
                if (processedData != null) {
                    //硬件编码
                    onProcessedYuvFrame(processedData, pts);
                } else {
                    //如果转码失败，提示 转码失败
                    mHandler.notifyEncodeIllegalArgumentException(new IllegalArgumentException("libyuv failure"));
                }
            }


            if (networkWeakTriggered) {
                networkWeakTriggered = false;
                mHandler.notifyNetworkResume();
            }
        } else {
            //如果视频混合器获取到的 缓存计数为空或者大于VGOP，则提示弱网，没有
            mHandler.notifyNetworkWeak();
            networkWeakTriggered = true;
        }
    }

    //处理得到的NV21 格式数据
    public void onGetYuvNV21Frame(byte[] data, int width, int height, Rect boundingBox) {
        // Check video frame cache number to judge the networking situation.
        // Just cache GOP / FPS seconds data according to latency.
        //从混合器中获取视频帧 缓存计数
        AtomicInteger videoFrameCacheNumber = flvMuxer.getVideoFrameCacheNumber();
        //计数不为空并小于最大数
        if (videoFrameCacheNumber != null && videoFrameCacheNumber.get() < VGOP) {
            //计算时间戳
            long pts = System.nanoTime() / 1000 - mPresentTimeUs;
            //如果是软编码，抛异常 不支持-----------------------------为啥不支持
            if (useSoftEncoder) {
                throw new UnsupportedOperationException("Not implemented");
                //swRgbaFrame(data, width, height, pts);
            } else {
                //硬编码 为啥有支持
                byte[] processedData = hwYUVNV21FrameScaled(data, width, height, boundingBox);
                if (processedData != null) {
                    onProcessedYuvFrame(processedData, pts);
                } else {
                    mHandler.notifyEncodeIllegalArgumentException(new IllegalArgumentException("libyuv failure"));
                }
            }

            if (networkWeakTriggered) {
                networkWeakTriggered = false;
                mHandler.notifyNetworkResume();
            }
        } else {
            mHandler.notifyNetworkWeak();
            networkWeakTriggered = true;
        }
    }

    public void onGetArgbFrame(int[] data, int width, int height, Rect boundingBox) {
        // Check video frame cache number to judge the networking situation.
        // Just cache GOP / FPS seconds data according to latency.
        AtomicInteger videoFrameCacheNumber = flvMuxer.getVideoFrameCacheNumber();
        if (videoFrameCacheNumber != null && videoFrameCacheNumber.get() < VGOP) {
            long pts = System.nanoTime() / 1000 - mPresentTimeUs;
            if (useSoftEncoder) {
                throw new UnsupportedOperationException("Not implemented");
                //swArgbFrame(data, width, height, pts);
            } else {
                //这两种格式是干哈的？？？？？
                byte[] processedData = hwArgbFrameScaled(data, width, height, boundingBox);
                if (processedData != null) {
                    onProcessedYuvFrame(processedData, pts);
                } else {
                    mHandler.notifyEncodeIllegalArgumentException(new IllegalArgumentException("libyuv failure"));
                }
            }

            if (networkWeakTriggered) {
                networkWeakTriggered = false;
                mHandler.notifyNetworkResume();
            }
        } else {
            mHandler.notifyNetworkWeak();
            networkWeakTriggered = true;
        }
    }

    public void onGetArgbFrame(int[] data, int width, int height) {
        // Check video frame cache number to judge the networking situation.
        // Just cache GOP / FPS seconds data according to latency.
        AtomicInteger videoFrameCacheNumber = flvMuxer.getVideoFrameCacheNumber();
        if (videoFrameCacheNumber != null && videoFrameCacheNumber.get() < VGOP) {
            long pts = System.nanoTime() / 1000 - mPresentTimeUs;
            if (useSoftEncoder) {
                throw new UnsupportedOperationException("Not implemented");
                //swArgbFrame(data, width, height, pts);
            } else {
                byte[] processedData = hwArgbFrame(data, width, height);
                if (processedData != null) {
                    onProcessedYuvFrame(processedData, pts);
                } else {
                    mHandler.notifyEncodeIllegalArgumentException(new IllegalArgumentException("libyuv failure"));
                }
            }

            if (networkWeakTriggered) {
                networkWeakTriggered = false;
                mHandler.notifyNetworkResume();
            }
        } else {
            mHandler.notifyNetworkWeak();
            networkWeakTriggered = true;
        }
    }

    // Y, U (Cb) and V (Cr)
    // yuv420                     yuv yuv yuv yuv
    // yuv420p (planar)   yyyy*2 uu vv
    // yuv420sp(semi-planner)   yyyy*2 uv uv
    // I420 -> YUV420P   yyyy*2 uu vv
    // YV12 -> YUV420P   yyyy*2 vv uu
    // NV12 -> YUV420SP  yyyy*2 uv uv
    // NV21 -> YUV420SP  yyyy*2 vu vu
    // NV16 -> YUV422SP  yyyy uv uv
    // YUY2 -> YUV422SP  yuyv yuyv
    //硬件编码处理rgb数据
    private byte[] hwRgbaFrame(byte[] data, int width, int height) {
        //判断当前的 视频编码 数据格式  -- 为啥要旋转180度
        switch (mVideoColorFormat) {
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
                return RGBAToI420(data, width, height, true, 180);
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
                return RGBAToNV12(data, width, height, true, 180);
            default:
                throw new IllegalStateException("Unsupported color format!");
        }
    }

    private byte[] hwYUVNV21FrameScaled(byte[] data, int width, int height, Rect boundingBox) {
        switch (mVideoColorFormat) {
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
                return NV21ToI420Scaled(data, width, height, true, 180, boundingBox.left, boundingBox.top, boundingBox.width(), boundingBox.height());
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
                return NV21ToNV12Scaled(data, width, height, true, 180, boundingBox.left, boundingBox.top, boundingBox.width(), boundingBox.height());
            default:
                throw new IllegalStateException("Unsupported color format!");
        }
    }

    private byte[] hwArgbFrameScaled(int[] data, int width, int height, Rect boundingBox) {
        switch (mVideoColorFormat) {
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
                return ARGBToI420Scaled(data, width, height, false, 0, boundingBox.left, boundingBox.top, boundingBox.width(), boundingBox.height());
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
                return ARGBToNV12Scaled(data, width, height, false, 0, boundingBox.left, boundingBox.top, boundingBox.width(), boundingBox.height());
            default:
                throw new IllegalStateException("Unsupported color format!");
        }
    }

    private byte[] hwArgbFrame(int[] data, int inputWidth, int inputHeight) {
        switch (mVideoColorFormat) {
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
                return ARGBToI420(data, inputWidth, inputHeight, false, 0);
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
                return ARGBToNV12(data, inputWidth, inputHeight, false, 0);
            default:
                throw new IllegalStateException("Unsupported color format!");
        }
    }

    //软编码
    private void swRgbaFrame(byte[] data, int width, int height, long pts) {
        //这里固定旋转180度
        RGBASoftEncode(data, width, height, true, 180, pts);
    }

    //创建音频录制对象，audioResource，采样率，声道格式，音频格式（立体声），缓存大小
    public AudioRecord chooseAudioRecord() {
        AudioRecord mic = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, SrsEncoder.ASAMPLERATE,
            AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, getPcmBufferSize() * 4);
        //如果音频录制对象 状态不是初始化状态，
        if (mic.getState() != AudioRecord.STATE_INITIALIZED) {
            //设置为单声道
            mic = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, SrsEncoder.ASAMPLERATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, getPcmBufferSize() * 4);
            //如果音频录制对象还是 未初始化状态，将音频录制对象置空
            if (mic.getState() != AudioRecord.STATE_INITIALIZED) {
                mic = null;
            } else {
                //声道配置单声道
                SrsEncoder.aChannelConfig = AudioFormat.CHANNEL_IN_MONO;
            }
        } else {
            //声道配置标志 设置为立体声
            SrsEncoder.aChannelConfig = AudioFormat.CHANNEL_IN_STEREO;
        }

        return mic;
    }

    //获取pcm 缓存数据
    private int getPcmBufferSize() {
        //通过采样率，声道格式，音频采样大小 —+ 8191 。最后算出8191 的整数倍pcm缓存大小
        int pcmBufSize = AudioRecord.getMinBufferSize(ASAMPLERATE, AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT) + 8191;
        return pcmBufSize - (pcmBufSize % 8192);
    }

    // choose the video encoder by name.
    //通过名称选择视频编码器
    private MediaCodecInfo chooseVideoEncoder(String name) {
        //获取支持的视频编码器的数量
        int nbCodecs = MediaCodecList.getCodecCount();
        //遍历编码器
        for (int i = 0; i < nbCodecs; i++) {
            //获取编码器信息
            MediaCodecInfo mci = MediaCodecList.getCodecInfoAt(i);
            //如果不是编码器则跳过
            if (!mci.isEncoder()) {
                continue;
            }

            //获取编码器锁支持的类型
            String[] types = mci.getSupportedTypes();
            //遍历编码器支持的类型
            for (int j = 0; j < types.length; j++) {
                //如果类型等于"video/avc",没有编码器名称要求直接返回编码器，有编码器名称要求处理是否名称一致
                if (types[j].equalsIgnoreCase(VCODEC)) {
                    Log.i(TAG, String.format("vencoder %s types: %s", mci.getName(), types[j]));
                    if (name == null) {
                        return mci;
                    }

                    if (mci.getName().contains(name)) {
                        return mci;
                    }
                }
            }
        }

        return null;
    }

    // choose the right supported color format. @see below:
    //选择支持的视频编码器
    private int chooseVideoEncoder() {
        // choose the encoder "video/avc":
        //      1. select default one when type matched.
        //      2. google avc is unusable.
        //      3. choose qcom avc.
        //选择 类型是"video/avc"的编码器，默认选择类型一致就行，如果avc不可以，就选择qcom avc
        vmci = chooseVideoEncoder(null);
        //vmci = chooseVideoEncoder("google");
        //vmci = chooseVideoEncoder("qcom");

        //适配的颜色格式
        int matchedColorFormat = 0;
        //
        MediaCodecInfo.CodecCapabilities cc = vmci.getCapabilitiesForType(VCODEC);
        for (int i = 0; i < cc.colorFormats.length; i++) {
            int cf = cc.colorFormats[i];
            Log.i(TAG, String.format("vencoder %s supports color fomart 0x%x(%d)", vmci.getName(), cf, cf));

            // choose YUV for h.264, prefer the bigger one.
            // corresponding to the color space transform in onPreviewFrame
            if (cf >= cc.COLOR_FormatYUV420Planar && cf <= cc.COLOR_FormatYUV420SemiPlanar) {
                if (cf > matchedColorFormat) {
                    matchedColorFormat = cf;
                }
            }
        }

        for (int i = 0; i < cc.profileLevels.length; i++) {
            MediaCodecInfo.CodecProfileLevel pl = cc.profileLevels[i];
            Log.i(TAG, String.format("vencoder %s support profile %d, level %d", vmci.getName(), pl.profile, pl.level));
        }

        Log.i(TAG, String.format("vencoder %s choose color format 0x%x(%d)", vmci.getName(), matchedColorFormat, matchedColorFormat));
        return matchedColorFormat;
    }

    private native void setEncoderResolution(int outWidth, int outHeight);
    private native void setEncoderFps(int fps);
    private native void setEncoderGop(int gop);
    private native void setEncoderBitrate(int bitrate);
    private native void setEncoderPreset(String preset);
    private native byte[] RGBAToI420(byte[] frame, int width, int height, boolean flip, int rotate);
    private native byte[] RGBAToNV12(byte[] frame, int width, int height, boolean flip, int rotate);
    private native byte[] ARGBToI420Scaled(int[] frame, int width, int height, boolean flip, int rotate, int crop_x, int crop_y,int crop_width, int crop_height);
    private native byte[] ARGBToNV12Scaled(int[] frame, int width, int height, boolean flip, int rotate, int crop_x, int crop_y,int crop_width, int crop_height);
    private native byte[] ARGBToI420(int[] frame, int width, int height, boolean flip, int rotate);
    private native byte[] ARGBToNV12(int[] frame, int width, int height, boolean flip, int rotate);
    private native byte[] NV21ToNV12Scaled(byte[] frame, int width, int height, boolean flip, int rotate, int crop_x, int crop_y,int crop_width, int crop_height);
    private native byte[] NV21ToI420Scaled(byte[] frame, int width, int height, boolean flip, int rotate, int crop_x, int crop_y,int crop_width, int crop_height);
    private native int RGBASoftEncode(byte[] frame, int width, int height, boolean flip, int rotate, long pts);
    private native boolean openSoftEncoder();
    private native void closeSoftEncoder();

    static {
        System.loadLibrary("yuv");
        System.loadLibrary("enc");
    }
}
