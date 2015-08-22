package cn.ex.eyeophone;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.hardware.Camera.PictureCallback;
import android.hardware.Camera.ShutterCallback;
import android.media.Image;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceHolder.Callback;
import android.view.SurfaceView;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

@SuppressLint("NewApi")
public class MainFrameActivity extends Activity implements Callback{
    // Debugging
    private static final String TAG = "EyeOPhone";
    private static final boolean D = true;
    public static void log(String msg){
    	if(D){
    		Log.e(TAG, msg);
    	}
    }
    // Layout Views
    
  //预览相关参数
    private SurfaceView mSurface = null;
	private SurfaceHolder mHolder = null;
	private Camera mCamera = null;
	private boolean previewState = false; 
	//图片质量分辨率
	//标清
	private final int LOWQ_HEIGHT = 240;
	private final int LOWQ_WIDTH = 320;
	private final int LOWQ = 40;
	//高清
	private final int HEIGHTQ_HEIGHT = 480;
	private final int HEIGHTQ_WIDTH = 640;
	private final int HEIGHTQ = 70;
	//发送视频宽度,高度     默认低质量
	private int VideoWidth=HEIGHTQ_WIDTH;
	private int VideoHeight=HEIGHTQ_HEIGHT;
	private int VideoQuality=LOWQ;
//	private int VideoWidth=LOWQ_WIDTH;
//	private int VideoHeight=LOWQ_HEIGHT;
//	private int VideoQuality=LOWQ;
	//存放数据队列
	//网络参数
	private DatagramSocket mSendSocket;
	private DatagramPacket sPacket;
	private String remoteIP;
	private DatagramSocket recvSocket;// 接收数据Socket
	private DatagramPacket rPacket;
	private byte[] rBuffer = new byte[20];  //接收指令缓存
	private int remotePort = 8430;   		//远程数据端口，用于图像数据和广播
	private int localDataPort = 8433;    	//本地UDP端口
	private boolean mIsNetConnected;   		//网络设备连接状态
	//ble
	//蓝牙4.0的UUID,其中0000ffe1-0000-1000-8000-00805f9b34fb是广州汇承信息科技有限公司08蓝牙模块的UUID
	public static String HEART_RATE_MEASUREMENT = "0000ffe1-0000-1000-8000-00805f9b34fb";
	public static String EXTRAS_DEVICE_NAME = "DEVICE_NAME";
	public static String EXTRAS_DEVICE_ADDRESS = "DEVICE_ADDRESS";
	public static String EXTRAS_DEVICE_RSSI = "RSSI";
	//蓝牙连接状态
	private boolean mConnected = false;
	private String CONNECTED = "connected";
	private String DISCONNECTED = "disconnected";
	private String status = DISCONNECTED;
	//蓝牙名字
	private String mDeviceName;
	//蓝牙地址
	private String mDeviceAddress;
	//蓝牙信号值
	private String mRssi;
	private Bundle b;
	//蓝牙service,负责后台的蓝牙服务
	public static BluetoothLeService mBluetoothLeService;
	private ArrayList<ArrayList<BluetoothGattCharacteristic>> mGattCharacteristics = new ArrayList<ArrayList<BluetoothGattCharacteristic>>();
	//蓝牙特征值
	private BluetoothGattCharacteristic target_chara = null;
	
	private Handler mhandler = new Handler();
	private Handler myHandler = new Handler()
	{
		// 2.重写消息处理函数
		public void handleMessage(Message msg)
		{
			switch (msg.what)
			{
			case 1:
			{
				// 更新View
				String state = msg.getData().getString("connect_state");
				int viewId = msg.getData().getInt("viewId");
				TextView textview;
				if(viewId == 0){   //ble连接状态
					textview = connect_state;
					log("ble connecttion change");
				}else{      //网络设备连接状态
					textview = net_connect_state;
					log("net connecttion change");
				}
				textview.setText(state);
				if(CONNECTED.equals(state)){
					textview.setTextColor(0xff3FABCD);   //green
				}else{
					textview.setTextColor(0xffff0000);   //red
				}
				break;
			}
			}
			super.handleMessage(msg);
		}
	};
	private TextView connect_state;
	private TextView net_connect_state;
	//线程
	//发送线程
	private HandlerThread mSendThread;
	private Handler mSendThreadHandler;
	private boolean mSendThreadFlag;
	//接收线程
	private HandlerThread mRecvThread;
	private Handler mRecvHandler;
	private boolean mRecvFlag;
	//广播线程
	private HandlerThread mBroadcastThread;
	private Handler mBroadcastHandler;
	private boolean mBroadcastFlag;
	//camera线程
	private CameraThread mCameraThread;
    private Handler mCameraHandler;
	
	private static List<ByteArrayOutputStream> sendQueue = Collections.synchronizedList(new LinkedList<ByteArrayOutputStream>());
	//调节亮度   好像对surface没用？？？？
	private boolean screenOn = false;
	
	PowerManager.WakeLock wl;
	
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if(D) Log.e(TAG, "+++ ON CREATE +++");
        // Set up the window layout
//        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);  //常亮
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.main);
        initView();
        initBle();
        initThread();
        //初始化socket
        if(!ConnectSocket()){
        	Toast.makeText(this, "端口不可用", Toast.LENGTH_SHORT).show();
        	finish();
        }
        //开启线程
        startSendThread();
        startBroadcastThread();
        startRecvThread();
        
     // _TEST 	
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"bright");  
        //点亮屏幕  
        wl.acquire();
    }
    /**
     * 初始化线程
     */
    private void initThread() {
        mBroadcastThread = new HandlerThread("broadcast thread");
        mBroadcastThread.start();
        mBroadcastHandler = new Handler(mBroadcastThread.getLooper()){
        	@Override
        	public void handleMessage(Message msg) {
        		log("开启广播ip线程");
        		while(mBroadcastFlag){
        			//发送广播信号
        			try {
        				sendBrocast();
						Thread.sleep(500);
					} catch (InterruptedException e) {
						log(e.getMessage());
					}catch(Exception e){
						log(e.getMessage());
					}
        		}
        		log("关闭广播ip线程");
        		super.handleMessage(msg);
        	}
        };
        //接收数据线程
        mRecvThread = new HandlerThread("receivethread");
        mRecvThread.start();
        mRecvHandler = new Handler(mRecvThread.getLooper()){
        	@Override
            public void handleMessage(Message msg) {
        		log("开启接收线程！");
            	while(mRecvFlag){
            		try{
            			recvData();
            		}catch(Exception e){
            			log("接收线程异常");
            			log(e.getMessage());
            		}
            	}
            	log("结束接收线程！");
            	super.handleMessage(msg);
            }
        };
        mCameraThread = new CameraThread("camerathread");
        mCameraThread.start();
        mCameraHandler = new Handler(mCameraThread.getLooper()){
        	@Override
        	public void handleMessage(Message msg) {
        		switch(msg.what){
        		case CameraThread.OPENCAMERA:  //打开相机
        			mCameraThread.openCamera();
        			break;
        		case CameraThread.CLOSECAMERA:  //关闭相机
        			mCameraThread.closeCamera();
        			break;
        		case CameraThread.TAKEPICTURE:  //关闭相机
        			mCameraThread.takePicture();
        			break;	
        		}
        		super.handleMessage(msg);
        	}
        };
        mSendThread = new HandlerThread("sendthread");
        mSendThread.start();
        mSendThreadHandler = new Handler(mSendThread.getLooper()){
        	@Override
            public void handleMessage(Message msg) { 

            	log("开启发送线程！");
    			try {
    				if(mSendSocket != null){
    					mSendSocket.close();
    					mSendSocket = null;
    				}
    				mSendSocket = new DatagramSocket(remotePort);
    			} catch (SocketException e1) {
    				log("new socket 异常");
    				log(e1.getMessage());
    			}
    			byte[] sendData;
        		while(mSendThreadFlag){
        			try {
        				synchronized (sendQueue) {
    						if(!sendQueue.isEmpty()){
    							sendData = sendQueue.remove(0).toByteArray();
    							sPacket = new DatagramPacket(sendData, sendData.length, InetAddress.getByName(remoteIP), remotePort);
    							mSendSocket.send(sPacket);
    						}
    					}
        				Thread.sleep(10);
        	        }catch (Exception e) {
        	        	log("未打开网络");
    					log(e.getMessage());
    				}
        		}
        		//关闭资源
        		if(mSendSocket != null){
        			mSendSocket.close();
        			mSendSocket = null;
        		}
        		log("结束发送线程");
               	super.handleMessage(msg);
            }
        };
        
    }
	private void initBle() {
    	b = getIntent().getExtras();
		//从意图获取显示的蓝牙信息
		mDeviceName = b.getString(EXTRAS_DEVICE_NAME);
		mDeviceAddress = b.getString(EXTRAS_DEVICE_ADDRESS);
		mRssi = b.getString(EXTRAS_DEVICE_RSSI);

		/* 启动蓝牙service */
		Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
		bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);
	}
	/**
     * 初始化控件
     */
    private void initView() {
    	//set up surfaceview
        this.mSurface = (SurfaceView)findViewById(R.id.surfaceview);
        this.mHolder = this.mSurface.getHolder();
        this.mHolder.addCallback(this);
        this.mHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
//        this.mSurface.setKeepScreenOn(true);
        
        connect_state = (TextView) findViewById(R.id.connect_state);
        net_connect_state = (TextView) findViewById(R.id.net_connect_state);
	}
	@Override
	protected void onResume()
	{
		super.onResume();
		//绑定广播接收器
		registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
		if (mBluetoothLeService != null)
		{    
			//根据蓝牙地址，建立连接
			final boolean result = mBluetoothLeService.connect(mDeviceAddress);
		}
		log("onresume");
	}
	@Override
	protected void onStop() {
//		closeCamera();
		super.onStop();
	}
	@Override
	protected void onRestart() {
//		if(mIsNetConnected){
//			openCamera();
//		}
		super.onRestart();
	}
    @Override
    public void onDestroy() {
        super.onDestroy();
        log("--- ON DESTROY ---");
        unregisterReceiver(mGattUpdateReceiver);
		mBluetoothLeService = null;
		//解除蓝牙服务
		unbindService(mServiceConnection);
		//结束子线程
		closeCamera();
		DisConnectSocket();   //关闭监听socket，结束阻塞状态，才能结束线程
		stopRecvThread();
		stopBroadcastThread();
		stopSendThread();
		mBroadcastThread.quitSafely();
		mRecvThread.quitSafely();
		mCameraThread.quitSafely();
		mSendThread.quitSafely();
		//释放  lock
        wl.release(); 
    }
   
    @Override
	public void surfaceDestroyed(SurfaceHolder holder) {
    	closeCamera();
	}
	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
	} 
	@Override
	public void surfaceCreated(SurfaceHolder holder) {
		if(mIsNetConnected){
			openCamera();
		}
	}

	//BluetoothLeService绑定的回调函数 
	private final ServiceConnection mServiceConnection = new ServiceConnection()
	{
		@Override
		public void onServiceConnected(ComponentName componentName,
				IBinder service)
		{
			mBluetoothLeService = ((BluetoothLeService.LocalBinder) service)
					.getService();
			if (!mBluetoothLeService.initialize())
			{
				finish();
			}
			// Automatically connects to the device upon successful start-up
			// initialization.
			// 根据蓝牙地址，连接设备
			mBluetoothLeService.connect(mDeviceAddress);
		}

		@Override
		public void onServiceDisconnected(ComponentName componentName)
		{
			mBluetoothLeService = null;
		}
	};
	/**
	 * 广播接收器，负责接收BluetoothLeService类发送的数据
	 */
	private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver()
	{
		@Override
		public void onReceive(Context context, Intent intent)
		{
			final String action = intent.getAction();
			if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action))//Gatt连接成功
			{
				mConnected = true;
				status = "connected";
				//更新连接状态
				updateConnectionState(status);
//				log("ble device connected");

			} else if (BluetoothLeService.ACTION_GATT_DISCONNECTED//Gatt连接失败
					.equals(action))
			{
				mConnected = false;
				status = "disconnected";
				//更新连接状态
				updateConnectionState(status);
//				log("ble device disconnected");

			} else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED//发现GATT服务器
					.equals(action))
			{
				// Show all the supported services and characteristics on the
				// user interface.
				//获取设备的所有蓝牙服务
				displayGattServices(mBluetoothLeService
						.getSupportedGattServices());
//				System.out.println("BroadcastReceiver :"+ "device SERVICES_DISCOVERED");
			} else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action))//有效数据
			{    
				//处理发送过来的数据
//				String revMsg = intent.getExtras().getString(BluetoothLeService.EXTRA_DATA);
			}
		}
	};
	/* 意图过滤器 */
	private static IntentFilter makeGattUpdateIntentFilter()
	{
		final IntentFilter intentFilter = new IntentFilter();
		intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
		intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
		intentFilter
				.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
		intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
		return intentFilter;
	}
	/** 
	* @Title: displayGattServices 
	* @Description: TODO(处理蓝牙服务) 
	* @param 无  
	* @return void  
	* @throws 
	*/ 
	@SuppressLint("NewApi")
	private void displayGattServices(List<BluetoothGattService> gattServices)
	{

		if (gattServices == null)
			return;
		String uuid = null;
//		String unknownServiceString = "unknown_service";
//		String unknownCharaString = "unknown_characteristic";

		// 服务数据,可扩展下拉列表的第一级数据
		ArrayList<HashMap<String, String>> gattServiceData = new ArrayList<HashMap<String, String>>();

		// 特征数据（隶属于某一级服务下面的特征值集合）
		ArrayList<ArrayList<HashMap<String, String>>> gattCharacteristicData = new ArrayList<ArrayList<HashMap<String, String>>>();

		// 部分层次，所有特征值集合
		mGattCharacteristics = new ArrayList<ArrayList<BluetoothGattCharacteristic>>();

		// Loops through available GATT Services.
		for (BluetoothGattService gattService : gattServices)
		{

			// 获取服务列表
			HashMap<String, String> currentServiceData = new HashMap<String, String>();
			uuid = gattService.getUuid().toString();

			// 查表，根据该uuid获取对应的服务名称。SampleGattAttributes这个表需要自定义。

			gattServiceData.add(currentServiceData);

			System.out.println("Service uuid:" + uuid);

			ArrayList<HashMap<String, String>> gattCharacteristicGroupData = new ArrayList<HashMap<String, String>>();

			// 从当前循环所指向的服务中读取特征值列表
			List<BluetoothGattCharacteristic> gattCharacteristics = gattService
					.getCharacteristics();

			ArrayList<BluetoothGattCharacteristic> charas = new ArrayList<BluetoothGattCharacteristic>();

			// Loops through available Characteristics.
			// 对于当前循环所指向的服务中的每一个特征值
			for (final BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics)
			{
				charas.add(gattCharacteristic);
				HashMap<String, String> currentCharaData = new HashMap<String, String>();
				uuid = gattCharacteristic.getUuid().toString();

				if (gattCharacteristic.getUuid().toString()
						.equals(HEART_RATE_MEASUREMENT))
				{
					// 测试读取当前Characteristic数据，会触发mOnDataAvailable.onCharacteristicRead()
					mhandler.postDelayed(new Runnable()
					{

						@Override
						public void run()
						{
							// TODO Auto-generated method stub
							mBluetoothLeService
									.readCharacteristic(gattCharacteristic);
						}
					}, 200);

					// 接受Characteristic被写的通知,收到蓝牙模块的数据后会触发mOnDataAvailable.onCharacteristicWrite()
					mBluetoothLeService.setCharacteristicNotification(
							gattCharacteristic, true);
					target_chara = gattCharacteristic;
					// 设置数据内容
					// 往蓝牙模块写入数据
					// mBluetoothLeService.writeCharacteristic(gattCharacteristic);
				}
				List<BluetoothGattDescriptor> descriptors = gattCharacteristic
						.getDescriptors();
				for (BluetoothGattDescriptor descriptor : descriptors)
				{
					System.out.println("---descriptor UUID:"
							+ descriptor.getUuid());
					// 获取特征值的描述
					mBluetoothLeService.getCharacteristicDescriptor(descriptor);
				}

				gattCharacteristicGroupData.add(currentCharaData);
			}
			// 按先后顺序，分层次放入特征值集合中，只有特征值
			mGattCharacteristics.add(charas);
			// 构件第二级扩展列表（服务下面的特征值）
			gattCharacteristicData.add(gattCharacteristicGroupData);
		}

	}
	/* 更新连接状态 */
	private void updateConnectionState(String status)
	{
		Message msg = new Message();
		msg.what = 1;
		Bundle b = new Bundle();
		b.putString("connect_state", status);
		b.putString("connect_state", status);
		b.putInt("viewId", 0);
		msg.setData(b);
		myHandler.sendMessage(msg);
	}
	/* 更新连接状态 */
	private void updateNetConnectionState(String status)
	{
		Message msg = new Message();
		msg.what = 1;
		Bundle b = new Bundle();
		b.putString("connect_state", status);
		b.putInt("viewId", 1);
		msg.setData(b);
		//将连接状态更新的UI的textview上
		myHandler.sendMessage(msg);
	}
	//接收数据
	private void recvData()
	{
		try
		{
			if(mIsNetConnected == true){
				//连接成功后设定心跳超时
				recvSocket.setSoTimeout(1000*10);
			}
			else {
				recvSocket.setSoTimeout(0);
			}
			recvSocket.receive(rPacket);
			String data = new String(rPacket.getData(), 0, rPacket.getLength());
			log("recv data:"+data);
			if(MyProtocol.CONNECT_REQ.equals(data)){   
				//连接成功
				remoteIP = rPacket.getAddress().getHostAddress();
				stopBroadcastThread();
				mIsNetConnected = true;
				updateNetConnectionState("connected");
//				mSurface.setKeepScreenOn(true);  //_TEST
				openCamera();
			}else if(MyProtocol.HEATBEAT.equals(data)){
				return;
			}else if(MyProtocol.TAKEPHO.equals(data)){  
				takePicture();
			}else{   //向ble设备发送指令
				sendToBle(data);
			}
		} catch(SocketTimeoutException e){
			//timeout
			updateNetConnectionState("disconnected");
            mIsNetConnected = false;
            closeCamera();
            startBroadcastThread();
//            mSurface.setKeepScreenOn(false);
            log("连接超时，退出");
		}catch (IOException ie){
			log("recvdata error:" + ie.getMessage());
			//socket 被关闭，结束receive阻塞，退出接收线程
		}catch(Exception e){
			log("camera exception");
			log(e.getMessage());
		}
	}
	//发动到ble终端
	public void sendToBle(String cmd){
		try{
			target_chara.setValue(cmd);
			//调用蓝牙服务的写特征值方法实现发送数据
			mBluetoothLeService.writeCharacteristic(target_chara);
//			log("sending to ble : "+cmd);
		}catch(Exception e){
			log("sendToBle 异常");
			log(e.getMessage());
		}
	}
	//绑定本地端口
	public boolean ConnectSocket()
	{
		try
		{
			if (recvSocket == null)
				recvSocket = new DatagramSocket(localDataPort);
			if (rPacket == null)
				rPacket = new DatagramPacket(rBuffer, rBuffer.length);
			return true;
		} catch (SocketException se)
		{
			DisConnectSocket();
			log("open udp port error:" + se.getMessage());
			return false;
		}
	}
	public void DisConnectSocket()
	{
		if (recvSocket != null)
		{
			recvSocket.close();
			recvSocket = null;
		}
		if (rPacket != null)
			rPacket = null;
	}
	//HandlerThread类的线程开关
	public void startBroadcastThread(){
		mBroadcastFlag = true;
		mBroadcastHandler.sendEmptyMessage(0);
	}
	public void startRecvThread(){
		mRecvFlag = true;
		mRecvHandler.sendEmptyMessage(0);
	}
	public void stopBroadcastThread(){
		mBroadcastFlag = false;
	}
	public void stopRecvThread(){
		mRecvFlag = false;
	}
	
    //拍照
    ShutterCallback myShutterCallback = new ShutterCallback() {
		public void onShutter() {
		}
	};
	PictureCallback myRawCallback = new PictureCallback() {
		public void onPictureTaken(byte[] data, Camera camera) {
		}
	};
	PictureCallback myjpegCalback = new PictureCallback() {
		public void onPictureTaken(byte[] data, Camera camera) {
			Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
			log("图片大小:"+data.length);
			savePicture(bitmap);
			openCamera();
		}
	};
	
	private class CameraThread extends HandlerThread implements Camera.PreviewCallback{
		
		public final static int OPENCAMERA = 0;
		public final static int CLOSECAMERA = 1;
		public final static int TAKEPICTURE = 2;
		
		public CameraThread(String name) {
			super(name);
		}
		@Override
		public void onPreviewFrame(byte[] data, Camera camera) {
	    	if(!mIsNetConnected){
	    		//未与网络设备连接
				return;
			}
	    	
			YuvImage image = null;
			if(data!=null) {
				//转换成YUV格式
				image = new YuvImage(data,ImageFormat.NV21, VideoWidth, VideoHeight,null);
				if(image!=null) {
					final ByteArrayOutputStream baos = new ByteArrayOutputStream(); 
					//在此设置图片的尺寸和质量 
					image.compressToJpeg(new Rect(0, 0, VideoWidth, VideoHeight), VideoQuality, baos);
					sendData(baos);
//					savePicture(image);   //保存到本地 for test
				}
	        }
		}
		private void openCamera() {
			try {
				closeCamera();
				mCamera = Camera.open();
				mCamera.setDisplayOrientation(90); //设置横行录制
				Camera.Parameters params = mCamera.getParameters();
				params.setPictureSize(1280, 720);
				params.setPreviewSize(VideoWidth, VideoHeight);
//				params.setPreviewFpsRange(12000, 20000);
				mCamera.setParameters(params);
				mCamera.setPreviewDisplay(mHolder);
				mCamera.startPreview();
				mCamera.setPreviewCallback(CameraThread.this);
				mCamera.autoFocus(null);  //very important
				log("open camera");
			} catch (IOException e) { 
				log("开启Camera exception");
				log(e.getMessage());
			}catch(Exception e){
				log("open 异常");
				log(e.getMessage());
			}
		}
		private void closeCamera() {
			if (mCamera == null) { return; }
			try {
				mCamera.reconnect();
				mCamera.stopPreview();
				mCamera.setPreviewCallback(null);
				mCamera.release();
				mCamera = null;
				log("close camera");
			} 
			catch (IOException e) { log(e.getMessage()); }
			  catch (RuntimeException e) { log(e.getMessage()); }
		}
		private void takePicture() {
			mCamera.takePicture(myShutterCallback, myRawCallback, myjpegCalback);
		}
    }
	
	private void openCamera() {
		mCameraHandler.sendEmptyMessage(CameraThread.OPENCAMERA);
	}
	/**
	 * 拍张功能的保存图片
	 * @param bm
	 */
	protected void savePicture(Bitmap bm) {
		String saveFolder = Environment.getExternalStorageDirectory()+"/000/";
		File f = new File(saveFolder);
		if(!f.exists()){
		    f.mkdir();
		}
		BufferedOutputStream bos = null;
		try{
			DateFormat df = new SimpleDateFormat("yyyyMMddHHmmss");
			File fTest = new File(saveFolder + df.format(new Date()) + ".JPG");
			FileOutputStream fout = new FileOutputStream(fTest);
			bos = new BufferedOutputStream(fout);
			bm.compress(Bitmap.CompressFormat.JPEG, 100, bos);
			Toast.makeText(this, "picture saved !!"+fTest.getName(),Toast.LENGTH_SHORT).show();
		}catch(Exception e){
			
		}finally{
			try{
				bos.flush();
				bos.close();
			}catch(IOException e){
				
			}
		}
	}
	/**
	 * 经onPreviewFrame 的帧保存到本地，用于测试
	 * @param bm
	 */
	protected void savePicture(YuvImage bm) {
		String saveFolder = Environment.getExternalStorageDirectory()+"/111/";
		File f = new File(saveFolder);
		if(!f.exists()){
		    f.mkdir();
		}
		BufferedOutputStream bos = null;
		try{
			DateFormat df = new SimpleDateFormat("mmss-SSS");
			File fTest = new File(saveFolder + df.format(new Date()) + ".JPG");
			FileOutputStream fout = new FileOutputStream(fTest);
			bos = new BufferedOutputStream(fout);
			bm.compressToJpeg(new Rect(0, 0, VideoWidth, VideoHeight), VideoQuality, bos);
		}catch(Exception e){
			
		}finally{
			try{
				bos.flush();
				bos.close();
			}catch(IOException e){
			}
		}
	}
	private void closeCamera() {
		mCameraHandler.sendEmptyMessage(CameraThread.CLOSECAMERA);
	}
	private void takePicture() {
		mCameraHandler.sendEmptyMessage(CameraThread.TAKEPICTURE);
	}
	private void startSendThread(){
    	mSendThreadFlag = true;
    	mSendThreadHandler.sendEmptyMessage(0);
    }
    private void stopSendThread(){
    	mSendThreadFlag = false;
    }
  //添加到发送队列中
    public void sendData(ByteArrayOutputStream baos)
	{
    	sendQueue.add(baos);
	}
    public void sendBrocast()
	{
    	try{
    		byte[] data = MyProtocol.BROADCAST.getBytes();
    		sPacket = new DatagramPacket(data, data.length, InetAddress.getByName("255.255.255.255"), remotePort);
    		mSendSocket.send(sPacket);
    	}catch(UnknownHostException e){
    		log("UnknownHostException");
    		log(e.getMessage());
    	}catch(IOException e){
    		log("send ioException");
    		log(e.getMessage());
    	}catch(Exception e){
    		log(e.getMessage());
    	}
	}
    //设置屏幕亮度
    public void screenBrightness(float value) {
    	try {
    		WindowManager.LayoutParams layout = getWindow().getAttributes();
    		layout.screenBrightness = value; //0最弱 1最亮
    		getWindow().setAttributes(layout);
    	} catch (Exception e) {
    	}
    	}
}