package net.ossrs.yasea;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.util.AttributeSet;

import com.seu.magicfilter.base.gpuimage.GPUImageFilter;
import com.seu.magicfilter.utils.MagicFilterFactory;
import com.seu.magicfilter.utils.MagicFilterType;
import com.seu.magicfilter.utils.OpenGLUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Created by Leo Ma on 2016/2/25.
 */
public class


SrsCameraView extends GLSurfaceView implements GLSurfaceView.Renderer {

    //特效
    private GPUImageFilter magicFilter;
    //视图view
    private SurfaceTexture surfaceTexture;
    //openGL纹理id
    private int mOESTextureId = OpenGLUtils.NO_TEXTURE;
    //视图宽、高。 预览的的宽高
    private int mSurfaceWidth;
    private int mSurfaceHeight;
    private int mPreviewWidth;
    private int mPreviewHeight;
    //是否已经编码
    private volatile boolean mIsEncoding;
    //是否打开手电筒
    private boolean mIsTorchOn = false;
    //输入的纵横比，即宽高比
    private float mInputAspectRatio;
    //输出的纵横比，宽高比
    private float mOutputAspectRatio;
    //物理矩阵
    private float[] mProjectionMatrix = new float[16];
    //相机矩阵
    private float[] mSurfaceMatrix = new float[16];
    //变换后的矩阵
    private float[] mTransformMatrix = new float[16];

    //相机
    private Camera mCamera;
    //特效buffer
    private ByteBuffer mGLPreviewBuffer;
    //相机id
    private int mCamId = -1;
    //预览角度--------------------------------------------------------这个是否可以改了，适应横屏机器
    private int mPreviewRotation = 90;
    //预览是横屏还是竖屏，这里默认的是竖屏------------------------------结合预览角度可以修改
    private int mPreviewOrientation = Configuration.ORIENTATION_PORTRAIT;

    //线程
    private Thread worker;
    //对象锁
    private final Object writeLock = new Object();
    //线程安全的链表队列，存储的数据是nio的IntBuffer
    private ConcurrentLinkedQueue<IntBuffer> mGLIntBufferCache = new ConcurrentLinkedQueue<>();
    //回调预览数据
    private PreviewCallback mPrevCb;

    public SrsCameraView(Context context) {
        this(context, null);
    }

    public SrsCameraView(Context context, AttributeSet attrs) {
        super(context, attrs);

        //设置openGL的版本为2 。清单文件中也要设置
        setEGLContextClientVersion(2);
        //设置渲染器会回调 方法 onSurfaceCreated onSurfaceChanged onDrawFrame
        setRenderer(this);
        //设置渲染模式，RENDERMODE_WHEN_DIRTY 和 RENDERMODE_CONTINUOUSLY，前者是懒惰渲染，需要手动调用 glSurfaceView.requestRender() 才会进行更新，而后者则是不停渲染。
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        // 关闭服务器端GL功能,在GL中很多都是一对一对的,比如这个的另一个gl.glEnable(...).
        GLES20.glDisable(GL10.GL_DITHER);
        // 清除屏幕颜色  给默认黑色
        GLES20.glClearColor(0, 0, 0, 0);

        //特效，默认无特效
        magicFilter = new GPUImageFilter(MagicFilterType.NONE);
        magicFilter.init(getContext().getApplicationContext());
        //给特效设置预览的宽和高
        magicFilter.onInputSizeChanged(mPreviewWidth, mPreviewHeight);

        //创建纹理对象id
        mOESTextureId = OpenGLUtils.getExternalOESTextureID();
        //给视图View 设置纹理对象id
        surfaceTexture = new SurfaceTexture(mOESTextureId);

        //在start preview之前设置callback 接收到可用像素数据前的回到
        surfaceTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
            @Override
            public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                //有可用的像素数据，重新渲染
                requestRender();
            }
        });

        // For camera preview on activity creation
        if (mCamera != null) {
            try {
                //启动相机
                mCamera.setPreviewTexture(surfaceTexture);
            } catch (IOException ioe) {
                ioe.printStackTrace();
            }
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        //设置画布的布局位置
        GLES20.glViewport(0, 0, width, height);
        //视图的宽、高
        mSurfaceWidth = width;
        mSurfaceHeight = height;
        //特效设置宽高 改变
        magicFilter.onDisplaySizeChanged(width, height);

        //输出的宽高比、纵横比。取值大于1 。这里是View的宽和高
        mOutputAspectRatio = width > height ? (float) width / height : (float) height / width;

        //设置特效 宽高比
        float aspectRatio = mOutputAspectRatio / mInputAspectRatio;
        //根据宽高、宽高比设置
        if (width > height) {
            Matrix.orthoM(mProjectionMatrix, 0, -1.0f, 1.0f, -aspectRatio, aspectRatio, -1.0f, 1.0f);
        } else {
            Matrix.orthoM(mProjectionMatrix, 0, -aspectRatio, aspectRatio, -1.0f, 1.0f, -1.0f, 1.0f);
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        //清除画布颜色 ，重置为黑色
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        //更新surfaceTexture的视图，渲染最近的图像数据 -----------------------surfaceTexture是否可以理解为holder
        surfaceTexture.updateTexImage();

        //surfaceTexture设置变换的矩阵
        surfaceTexture.getTransformMatrix(mSurfaceMatrix);
        //合并物理矩阵和相机视图矩阵
        Matrix.multiplyMM(mTransformMatrix, 0, mSurfaceMatrix, 0, mProjectionMatrix, 0);
        //特效设置变换矩阵
        magicFilter.setTextureTransformMatrix(mTransformMatrix);
        //特效根据纹理id 设置参数
        magicFilter.onDrawFrame(mOESTextureId);

        //如果是加特效
        if (mIsEncoding) {
            //mGLIntBufferCache 缓存添加特效对象生成的IntBuffer
            mGLIntBufferCache.add(magicFilter.getGLFboBuffer());
            //加对象锁。 目的：处理完一帧然后允许第二针使用buff以免错乱
            synchronized (writeLock) {
                //释放锁
                writeLock.notifyAll();
            }
        }
    }

    //设置预览回调------------------------这个地方是否是硬件编码拿出来的
    public void setPreviewCallback(PreviewCallback cb) {
        mPrevCb = cb;
    }

    //获取相机对象
    public Camera getCamera(){
        return this.mCamera;
    }
    //设置相机预览回调
    public void setPreviewCallback(Camera.PreviewCallback previewCallback){
        this.mCamera.setPreviewCallback(previewCallback);
    }


    /**
     * 设置预览分辨率
     */
    public int[] setPreviewResolution(int width, int height) {
        //从GLSurfaceView中拿到holder 然后设置填充大小。宽高值是传递进来的
        getHolder().setFixedSize(width, height);

        //打开相机
        mCamera = openCamera();
        //变量记录 预览的宽高
        mPreviewWidth = width;
        mPreviewHeight = height;
        //获取设置支持的宽高,如果找到则用系统支持的
        Camera.Size rs = adaptPreviewResolution(mCamera.new Size(width, height));
        if (rs != null) {
            mPreviewWidth = rs.width;
            mPreviewHeight = rs.height;
        }
        //相机参数设置 预览的宽高
        mCamera.getParameters().setPreviewSize(mPreviewWidth, mPreviewHeight);

        //创建GL预览buf的内存
        mGLPreviewBuffer = ByteBuffer.allocate(mPreviewWidth * mPreviewHeight * 4);

        //输入的宽高比，这里是预览视图的宽和高。取值大于大于1
        mInputAspectRatio = mPreviewWidth > mPreviewHeight ?
            (float) mPreviewWidth / mPreviewHeight : (float) mPreviewHeight / mPreviewWidth;

        //返回预览 宽、高值
        return new int[] { mPreviewWidth, mPreviewHeight };
    }

    //设置过滤特效
    public boolean setFilter(final MagicFilterType type) {
        if (mCamera == null) {
            return false;
        }

        //添加任务，销毁当前的的特效对象，创建新的特效，并设置输入的预览宽高、展示W用的View的宽高
        //          特效设置三部：初始化、设置输入的预览宽高、视图的宽高
        queueEvent(new Runnable() {
            @Override
            public void run() {
                if (magicFilter != null) {
                    magicFilter.destroy();
                }
                magicFilter = MagicFilterFactory.initFilters(type);
                if (magicFilter != null) {
                    magicFilter.init(getContext().getApplicationContext());
                    magicFilter.onInputSizeChanged(mPreviewWidth, mPreviewHeight);
                    magicFilter.onDisplaySizeChanged(mSurfaceWidth, mSurfaceHeight);
                }
            }
        });
        //重新渲染
        requestRender();
        return true;
    }

    /**
     * 删除纹理对象
     */
    private void deleteTextures() {
        if (mOESTextureId != OpenGLUtils.NO_TEXTURE) {
            queueEvent(new Runnable() {
                @Override
                public void run() {
                    GLES20.glDeleteTextures(1, new int[]{ mOESTextureId }, 0);
                    mOESTextureId = OpenGLUtils.NO_TEXTURE;
                }
            });
        }
    }

    /**
     * 设置摄像头id，切换摄像头。
     */
    public void setCameraId(int id) {
        //先停止手电筒
        stopTorch();
        mCamId = id;
        //设置预览方向：横屏还是纵屏
        setPreviewOrientation(mPreviewOrientation);
    }

    /**
     * 设置预览方向：横屏还是纵屏
     */
    public void setPreviewOrientation(int orientation) {
        //预览方向
        mPreviewOrientation = orientation;
        //拿到相机信息对象
        Camera.CameraInfo info = new Camera.CameraInfo();
        //根据相机id 获取对应的相机信息
        Camera.getCameraInfo(mCamId, info);
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            //竖屏的处理
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                //如果是前置摄像头
                mPreviewRotation = info.orientation % 360;//方向对360取余
                mPreviewRotation = (360 - mPreviewRotation) % 360;  // compensate the mirror 补偿镜像怎么理解？
            } else {
                //
                mPreviewRotation = (info.orientation + 360) % 360;
            }
        } else if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                mPreviewRotation = (info.orientation - 90) % 360;
                mPreviewRotation = (360 - mPreviewRotation) % 360;  // compensate the mirror
            } else {
                mPreviewRotation = (info.orientation + 90) % 360;
            }
        }
    }

    //获取相机的id
    public int getCameraId() {
        return mCamId;
    }

    //轮询：线程是否中断
    //          轮询特效缓存：如果不为空则一直轮询处理 ---------等待特效加入，有特效后从特效对象中拿出像素数据
    //                       ----------疑问：如果没有特效，那像素数据从哪里来-- 是否是空特效对象获取
                            //若特效为空 则进入等待500ms，等待
    public void enableEncoding() {
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!Thread.interrupted()) {
                    while (!mGLIntBufferCache.isEmpty()) {
                        IntBuffer picture = mGLIntBufferCache.poll();
                        mGLPreviewBuffer.asIntBuffer().put(picture.array());
                        mPrevCb.onGetRgbaFrame(mGLPreviewBuffer.array(), mPreviewWidth, mPreviewHeight);
                    }
                    // Waiting for next frame
                    synchronized (writeLock) {
                        try {
                            // isEmpty() may take some time, so we set timeout to detect next frame
                            writeLock.wait(500);
                        } catch (InterruptedException ie) {
                            worker.interrupt();
                        }
                    }
                }
            }
        });
        worker.start();
        //是否在编码中
        mIsEncoding = true;
    }

    //不允许编码
    public void disableEncoding() {
        //编码标志 设置为false
        mIsEncoding = false;
        //清除特效集合
        mGLIntBufferCache.clear();

        //中断编码线程
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
                worker.interrupt();
            }
            worker = null;
        }
    }

    //相机开启
    public boolean startCamera() {
        if (mCamera == null) {
            mCamera = openCamera();
            if (mCamera == null) {
                return false;
            }
        }

        //设置相机的图片的宽和高、预览的宽和高
        Camera.Parameters params = mCamera.getParameters();
        params.setPictureSize(mPreviewWidth, mPreviewHeight);
        params.setPreviewSize(mPreviewWidth, mPreviewHeight);
        //适配Fps范围（帧率范围）。找到相机所支持的都小的帧率范围
        int[] range = adaptFpsRange(SrsEncoder.VFPS, params.getSupportedPreviewFpsRange());
        params.setPreviewFpsRange(range[0], range[1]);
        //设置像素数据预览格式
        params.setPreviewFormat(ImageFormat.NV21);
        //设置闪光灯为关闭
        params.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
        //设置白平衡
        params.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_AUTO);
        //设置屏幕模式
        params.setSceneMode(Camera.Parameters.SCENE_MODE_AUTO);

        //获取相机所支持的获取焦点模式
        List<String> supportedFocusModes = params.getSupportedFocusModes();
        if (supportedFocusModes != null && !supportedFocusModes.isEmpty()) {
            if (supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                //图片持续性聚焦
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } else if (supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                //自动聚焦模式
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                mCamera.autoFocus(null);
            } else {
                //采用第一种聚焦模式
                params.setFocusMode(supportedFocusModes.get(0));
            }
        }

        //获取相机所支持的闪光灯模式
        List<String> supportedFlashModes = params.getSupportedFlashModes();
        if (supportedFlashModes != null && !supportedFlashModes.isEmpty()) {
            if (supportedFlashModes.contains(Camera.Parameters.FLASH_MODE_TORCH)) {
                if (mIsTorchOn) {
                    //手电筒模式
                    params.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
                }
            } else {
                params.setFlashMode(supportedFlashModes.get(0));
            }
        }

        //相机设置相机参数
        mCamera.setParameters(params);

        //相机设置预览角度
        mCamera.setDisplayOrientation(mPreviewRotation);

        try {
            //设置相机渲染对象
            mCamera.setPreviewTexture(surfaceTexture);
        } catch (IOException e) {
            e.printStackTrace();
        }
        //开启相机
        mCamera.startPreview();
        return true;
    }

    //停止相机
    public void stopCamera() {
        //不允许编码
        disableEncoding();

        //关闭手电筒
        stopTorch();
        //关闭预览回调、关闭预览、释放相机
        if (mCamera != null) {
            mCamera.setPreviewCallback(null);
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }
    }

    //打开相机
    private Camera openCamera() {
        Camera camera;
        //若相机id小于0，获取相机参数，获取相机支持的摄像头数量，遍历获取前后摄像头的索引位置，
        //先取前摄像头，否则取后摄像头，都没有设置为0
        //打开摄像头
        if (mCamId < 0) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            int numCameras = Camera.getNumberOfCameras();
            int frontCamId = -1;
            int backCamId = -1;
            for (int i = 0; i < numCameras; i++) {
                Camera.getCameraInfo(i, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                    backCamId = i;
                } else if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    frontCamId = i;
                    break;
                }
            }
            if (frontCamId != -1) {
                mCamId = frontCamId;
            } else if (backCamId != -1) {
                mCamId = backCamId;
            } else {
                mCamId = 0;
            }
        }
        camera = Camera.open(mCamId);
        return camera;
    }

    //找到最适合指定大小的预览尺寸
    private Camera.Size adaptPreviewResolution(Camera.Size resolution) {
        float diff = 100f;
        //纵横比
        float xdy = (float) resolution.width / (float) resolution.height;
        Camera.Size best = null;
        for (Camera.Size size : mCamera.getParameters().getSupportedPreviewSizes()) {
            //若找到同样的尺寸，则返回
            if (size.equals(resolution)) {
                return size;
            }
            //记录最合适的尺寸
            float tmp = Math.abs(((float) size.width / (float) size.height) - xdy);
            if (tmp < diff) {
                diff = tmp;
                best = size;
            }
        }
        return best;
    }

    //适配帧率范围
    private int[] adaptFpsRange(int expectedFps, List<int[]> fpsRanges) {
        expectedFps *= 1000;
        int[] closestRange = fpsRanges.get(0);
        int measure = Math.abs(closestRange[0] - expectedFps) + Math.abs(closestRange[1] - expectedFps);
        for (int[] range : fpsRanges) {
            if (range[0] <= expectedFps && range[1] >= expectedFps) {
                int curMeasure = Math.abs(range[0] - expectedFps) + Math.abs(range[1] - expectedFps);
                if (curMeasure < measure) {
                    closestRange = range;
                    measure = curMeasure;
                }
            }
        }
        return closestRange;
    }

    //开启手电筒
    public boolean startTorch() {
        if (mCamera != null) {
            //获取相机属性参数，获取属性中所支持的闪光模式，设置闪光模式是手电筒模式
            Camera.Parameters params = mCamera.getParameters();
            List<String> supportedFlashModes = params.getSupportedFlashModes();
            if (supportedFlashModes != null && !supportedFlashModes.isEmpty()) {
                if (supportedFlashModes.contains(Camera.Parameters.FLASH_MODE_TORCH)) {
                    params.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
                    mCamera.setParameters(params);
                    return true;
                }
            }
        }
        return false;
    }

    //关闭手电筒
    public void stopTorch() {
        if (mCamera != null) {
            //设置相机模式为关闭
            Camera.Parameters params = mCamera.getParameters();
            params.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
            mCamera.setParameters(params);
        }
    }

    //预览回调接口
    public interface PreviewCallback {
        void onGetRgbaFrame(byte[] data, int width, int height);
    }
}
