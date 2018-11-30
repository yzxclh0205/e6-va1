package net.ossrs.yasea;

import android.media.AudioRecord;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;

import com.github.faucamp.simplertmp.RtmpHandler;
import com.seu.magicfilter.utils.MagicFilterType;

import java.io.File;

/**
 * Created by Leo Ma on 2016/7/25.
 */
public class SrsPublisher {

    //音频录制对象
    private static AudioRecord mic;
    //回声消除对象：创建android.media.AudioRecord 的对象的时候，可以通过这个对象获取到一个audio session 的ID（获取的方法：getAudioSessionId()），
    // 这个ID的话在创建AcousticEchoCanceler的时候用到（创建对象：AcousticEchoCanceler.create(audioSessionId)）
    // ，最后播放音频的时候（这里是用AudioTrack播放）传入这个ID就行了。
    private static AcousticEchoCanceler aec;
//自动增强控制器    1，初始化播放器可控制器
//    private val mp = MediaPlayer()
//    private val control = AutomaticGainControl.create(mp.audioSessionId)
//    2，点击自动增强时判断状态
//        agcFunction.setOnClickListener {
//            if (AutomaticGainControl.isAvailable()) {
//                control.enabled = !control.enabled
//            } else {
//                Toast.makeText(this, "您的手机不支持自动增强", Toast.LENGTH_SHORT).show()
//            }
//        }
//    3，离开时关闭(同上)
    private static AutomaticGainControl agc;
    //pcm缓存数组
    private byte[] mPcmBuffer = new byte[4096];
    //音频线程
    private Thread aworker;

    //相机View（里头处理相机设置（视图宽高、预览宽高、特效）开启、停止相机）
    private SrsCameraView mCameraView;

    //只发送视频数据标志
    private boolean sendVideoOnly = false;
    //只发送音频数据标志
    private boolean sendAudioOnly = false;
    //视频帧 数量
    private int videoFrameCount;
    //最近的时间
    private long lastTimeMillis;
    //采样频率
    private double mSamplingFps;

    //Flv 混合器
    private SrsFlvMuxer mFlvMuxer;
    //Mp4 混合器
    private SrsMp4Muxer mMp4Muxer;
    //编码器
    private SrsEncoder mEncoder;

    //构造方法传入相机View，相机View设置预览回调 -----------数据从特效对象里头获取
    public SrsPublisher(SrsCameraView view) {
        mCameraView = view;
        mCameraView.setPreviewCallback(new SrsCameraView.PreviewCallback() {
            @Override
            public void onGetRgbaFrame(byte[] data, int width, int height) {
                //计算采样率，帧率
                calcSamplingFps();
                if (!sendAudioOnly) {
                    mEncoder.onGetRgbaFrame(data, width, height);
                }
            }
        });
    }


    //计算采样率、帧率
    private void calcSamplingFps() {
        // Calculate sampling FPS 如果视频帧 计数为0，记录当前时间
        if (videoFrameCount == 0) {
            lastTimeMillis = System.nanoTime() / 1000000;
            videoFrameCount++;
        } else {
            //如果视频帧 计数不为0，并且 视频帧的技术 已经大于等于编码数 这里是48
            // 计算差值 得到 n帧的处理时间
            if (++videoFrameCount >= SrsEncoder.VGOP) {
                long diffTimeMillis = System.nanoTime() / 1000000 - lastTimeMillis;

                //采样帧率 = 视频 帧数 * 1000 / 处理时间
                mSamplingFps = (double) videoFrameCount * 1000 / diffTimeMillis;
                //重置 视频帧数
                videoFrameCount = 0;
            }
        }
    }

    //开启相机
    public void startCamera() {
        mCameraView.startCamera();
    }

    //关闭相机
    public void stopCamera() {
        mCameraView.stopCamera();
    }

    //开启音频
    public void startAudio() {
        //创建音频录制对象
        mic = mEncoder.chooseAudioRecord();
        if (mic == null) {
            return;
        }

        //回声消除对象创建
        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(mic.getAudioSessionId());
            if (aec != null) {
                //设置处理回声消除
                aec.setEnabled(true);
            }
        }

        //自动增强控制器
        if (AutomaticGainControl.isAvailable()) {
            agc = AutomaticGainControl.create(mic.getAudioSessionId());
            if (agc != null) {
                //开启自动增强控制器
                agc.setEnabled(true);
            }
        }

        //音频录制线程
        aworker = new Thread(new Runnable() {
            @Override
            public void run() {
                //设置线程优先级 是音频录制
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
                //开启音频录制
                mic.startRecording();
                //音频录制对象没有中断 则一直循环读取
                while (!Thread.interrupted()) {
                    //如果是只发送视频，编码器处理pcm音频录制数据。--------------------只发送数据还处理音频数据
                    if (sendVideoOnly) {
                        mEncoder.onGetPcmFrame(mPcmBuffer, mPcmBuffer.length);
                        try {
                            // This is trivial...
                            Thread.sleep(20);
                        } catch (InterruptedException e) {
                            break;
                        }
                    } else {
                        //非只发送视频数据， 读取录制的pcm数据，如果读取数据长度不为0，则编码器处理音频数据
                        int size = mic.read(mPcmBuffer, 0, mPcmBuffer.length);
                        if (size > 0) {
                            mEncoder.onGetPcmFrame(mPcmBuffer, size);
                        }
                    }
                }
            }
        });
        //开启音频处理线程
        aworker.start();
    }

    //关闭音频
    public void stopAudio() {
        //若音频线程不为空，则中断线程，阻塞线程，置空线程
        if (aworker != null) {
            aworker.interrupt();
            try {
                aworker.join();
            } catch (InterruptedException e) {
                aworker.interrupt();
            }
            aworker = null;
        }

        //若音频录制对象不为空，则设置录制更新监听器为空，停止录制，释放音频录制资源
        if (mic != null) {
            mic.setRecordPositionUpdateListener(null);
            mic.stop();
            mic.release();
            mic = null;
        }

        //回声消除器不为空，则设置取消回声消除，释放资源，置空回声消除器对象
        if (aec != null) {
            aec.setEnabled(false);
            aec.release();
            aec = null;
        }

        //自动增强控制器不为空，则设置取消自动增强控制器，释放资源，置空自动增强控制器
        if (agc != null) {
            agc.setEnabled(false);
            agc.release();
            agc = null;
        }
    }

    //开启编码
    public void startEncode() {
        //若果编码器已经开始编码则返回
        if (!mEncoder.start()) {
            return;
        }

        //允许编码
        mCameraView.enableEncoding();

        //开始录音音频
        startAudio();
    }

    //停止编码
    public void stopEncode() {
        //停止音频录制
        stopAudio();
        //停止相机
        stopCamera();
        //编码器
        mEncoder.stop();
    }

    //开始推流
    public void startPublish(String rtmpUrl) {
        //混合器不为空，开始推流
        if (mFlvMuxer != null) {
            mFlvMuxer.start(rtmpUrl);
            //混合器设置分辨率
            mFlvMuxer.setVideoResolution(mEncoder.getOutputWidth(), mEncoder.getOutputHeight());
            //开始编码
            startEncode();
        }
    }

    //停止推流
    public void stopPublish() {
        if (mFlvMuxer != null) {
            //停止编码
            stopEncode();
            //停止混合
            mFlvMuxer.stop();
        }
    }

    //开始录制
    public boolean startRecord(String recPath) {
        return mMp4Muxer != null && mMp4Muxer.record(new File(recPath));
    }

    //停止录制
    public void stopRecord() {
        if (mMp4Muxer != null) {
            mMp4Muxer.stop();
        }
    }

    //暂停录制
    public void pauseRecord() {
        if (mMp4Muxer != null) {
            mMp4Muxer.pause();
        }
    }

    //恢复录制
    public void resumeRecord() {
        if (mMp4Muxer != null) {
            mMp4Muxer.resume();
        }
    }

    //切换到软编
    public void switchToSoftEncoder() {
        mEncoder.switchToSoftEncoder();
    }

    //切换到硬编
    public void switchToHardEncoder() {
        mEncoder.switchToHardEncoder();
    }

    //是否是软编
    public boolean isSoftEncoder() {
        return mEncoder.isSoftEncoder();
    }

    //预览宽
    public int getPreviewWidth() {
        return mEncoder.getPreviewWidth();
    }

    //预览高
    public int getPreviewHeight() {
        return mEncoder.getPreviewHeight();
    }

    //获取采样帧率
    public double getmSamplingFps() {
        return mSamplingFps;
    }

    //获取相机id
    public int getCamraId() {
        return mCameraView.getCameraId();
    }

    //设置预览分辨率。 相机的分辨率，编码的分辨率 -----------------设置编码器的预览分辨率是做啥？？？
    public void setPreviewResolution(int width, int height) {
        int resolution[] = mCameraView.setPreviewResolution(width, height);
        mEncoder.setPreviewResolution(resolution[0], resolution[1]);
    }

    //设置输出分辨率
    public void setOutputResolution(int width, int height) {
        if (width <= height) {
            mEncoder.setPortraitResolution(width, height);
        } else {
            mEncoder.setLandscapeResolution(width, height);
        }
    }

    //设置屏幕 横屏还是竖屏
    public void setScreenOrientation(int orientation) {
        mCameraView.setPreviewOrientation(orientation);
        mEncoder.setScreenOrientation(orientation);
    }

    //设置硬件编码模式
    public void setVideoHDMode() {
        mEncoder.setVideoHDMode();
    }

    //设置慢编码模式
    public void setVideoSmoothMode() {
        mEncoder.setVideoSmoothMode();
    }

    //是否只推视频流
    public void setSendVideoOnly(boolean flag) {
        //如果 音频录制对象不为空，设置只传视频，则停止录制音频，重置pcm数据，否则开始录制
        if (mic != null) {
            if (flag) {
                mic.stop();
                mPcmBuffer = new byte[4096];
            } else {
                mic.startRecording();
            }
        }
        sendVideoOnly = flag;
    }

    //设置只推音频标志
    public void setSendAudioOnly(boolean flag) {
        sendAudioOnly = flag;
    }

    //切换特效
    public boolean switchCameraFilter(MagicFilterType type) {
        return mCameraView.setFilter(type);
    }

    //切换摄像头
    public void switchCameraFace(int id) {
        //停止相机
        mCameraView.stopCamera();
        //开启对应相机
        mCameraView.setCameraId(id);
        //若id为零 则设置采用后置相机 ，否则选择前置相机
        if (id == 0) {
            mEncoder.setCameraBackFace();
        } else {
            mEncoder.setCameraFrontFace();
        }
        //编码器不为空，允许编码
        if (mEncoder != null && mEncoder.isEnabled()) {
            mCameraView.enableEncoding();
        }
        //打开相机
        mCameraView.startCamera();
    }

    //设置rtmp 处理器
    public void setRtmpHandler(RtmpHandler handler) {
        mFlvMuxer = new SrsFlvMuxer(handler);
        if (mEncoder != null) {
            mEncoder.setFlvMuxer(mFlvMuxer);
        }
    }

    //设置音频录制处理器
    public void setRecordHandler(SrsRecordHandler handler) {
        mMp4Muxer = new SrsMp4Muxer(handler);
        if (mEncoder != null) {
            mEncoder.setMp4Muxer(mMp4Muxer);
        }
    }

    //设置编码处理器
    public void setEncodeHandler(SrsEncodeHandler handler) {
        mEncoder = new SrsEncoder(handler);
        if (mFlvMuxer != null) {
            mEncoder.setFlvMuxer(mFlvMuxer);
        }
        if (mMp4Muxer != null) {
            mEncoder.setMp4Muxer(mMp4Muxer);
        }
    }
}
