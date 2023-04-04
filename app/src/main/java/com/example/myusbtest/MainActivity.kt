package com.example.myusbtest


import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED
import android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.example.myusbtest.databinding.ActivityMainBinding
import com.github.mjdev.libaums.UsbMassStorageDevice
import com.github.mjdev.libaums.fs.FileSystem
import com.github.mjdev.libaums.fs.UsbFile
import com.github.mjdev.libaums.fs.UsbFileStreamFactory
import com.github.mjdev.libaums.partition.Partition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.*


class MainActivity : AppCompatActivity() {

    lateinit var binding:ActivityMainBinding

    companion object {
        const val TAG = "MainActivity"
        const val ACTION_USB_PERMISSIONS = "com.example.myusbtest_USB_ACTION"

        fun fSize(sizeInByte: Double): String {
            if (sizeInByte < 1024) {
                return String.format("%s", sizeInByte);
            } else if (sizeInByte < 1024 * 1024) {
                return String.format(Locale.CANADA, "%.2fKB", sizeInByte / 1024);
            } else if (sizeInByte < 1024 * 1024 * 1024) {
                return String.format(Locale.CANADA, "%.2fMB", sizeInByte / 1024 / 1024);
            } else {
                return String.format(Locale.CANADA, "%.2fGB", sizeInByte / 1024 / 1024 / 1024);
            }
        }
    }

    private var storageDevices: Array<UsbMassStorageDevice>? = null;

    val mUsbReceiver = object: BroadcastReceiver() {
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            val device: UsbDevice? = intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            when(action){
                ACTION_USB_PERMISSIONS -> {
                    Toast.makeText(this@MainActivity, "Permission granted", Toast.LENGTH_SHORT).show()
                    //自定义Action
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        logShow("用户同意USB设备访问权限");
                        getUsbMass(device)?.let { readDevice(it) };
                    } else {
                        logShow("用户拒绝USB设备访问权限");
                    }
                }
                ACTION_USB_DEVICE_ATTACHED->{
                    logShow("U盘设备插入");
                    if (device != null) {
                        redUDiskDeviceList();
                    }
                }
                ACTION_USB_DEVICE_DETACHED->{
                    logShow("U盘设备移除");
                }
                else -> {
                    logShow("====Unknown action: $action =====");
                }
            }
        }
    }

    private fun readDevice(device: UsbMassStorageDevice) {
        try {
            device.init();//初始化
            //设备分区
            val partition:Partition = device.partitions[0];
            //文件系统
            val currentFs:FileSystem = partition.fileSystem;
            //可以获取到设备的标识
            currentFs.volumeLabel;
            //通过FileSystem可以获取当前U盘的一些存储信息，包括剩余空间大小，容量等等
            logShow("================== 设备详细 ==============================")
            logShow("Volume Label:  ${currentFs.volumeLabel}");
            logShow("Capacity:  ${fSize(currentFs.capacity.toDouble())}");
            logShow("Occupied Space:  ${fSize(currentFs.occupiedSpace.toDouble())})")
            logShow("Free Space:  ${fSize(currentFs.freeSpace.toDouble())}");
            logShow("Chunk size:  ${fSize(currentFs.chunkSize.toDouble())}");
            logShow("================================================")
            val dialog = LoadingDialog
                .Builder(this)
                .setMessage("Loading")
                .create().apply {
                    show()
                }
            GlobalScope.launch {
                readAndWriteTest(currentFs);
            }
            dialog.dismiss()
        } catch ( e: Exception) {
            e.printStackTrace();
        }
    }

    /**
     * 读写文件尝试
     *
     * @param currentFs
     */
    private suspend fun readAndWriteTest(currentFs: FileSystem) {
        withContext(Dispatchers.IO) {
            try {
                //设置当前文件对象为根目录
                val usbFile = currentFs.rootDirectory
                val files = usbFile.listFiles()

//                // 新建文件
//                val newFile = usbFile.createFile("test_${System.currentTimeMillis()}.txt")
//                logShow("新建文件: " + newFile.getName());
//
//                // 向U盘写入文件 写文件
//                val os = UsbFileStreamFactory.createBufferedOutputStream(newFile, currentFs);
//                os.write(("hi_" + System.currentTimeMillis()).toByteArray());
//                os.close();
                copyDriveToSdcard("/sdcard/MyDrive", files, currentFs)
                logShow("~~~文件加载完成~~~");
            }catch (e: Exception) {
                e.printStackTrace();
            }
        }
    }

    private fun copyDriveToSdcard(dirPath: String, files: Array<UsbFile>, currentFs: FileSystem){
            File(dirPath).apply{
                mkdirs()
                logShow("========  $dirPath ========")
                for (file in files){
                    logShow("文件: " + file.name);
                    if (file.isDirectory){
                        val dirFile = File(dirPath, file.name)
                        if (!dirFile.exists()){
                            dirFile.mkdirs()
                        }
                        copyDriveToSdcard(dirFile.absolutePath, file.listFiles(), currentFs)
                        logShow("===================")

                    }else{
                        val ips = UsbFileStreamFactory.createBufferedInputStream(file, currentFs);
                        val buffer = ByteArray(currentFs.chunkSize)
                        val sdOut = FileOutputStream(absolutePath+ "/" + file.name)
                        var len = ips.read(buffer)
                        while (len != -1) {
                            sdOut.write(buffer, 0, len)
                            len = ips.read(buffer)
                        }
                        sdOut.close()
                        ips.close()
                    }
                }
        }

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        init()
    }

    fun logShow(content: String){
        runOnUiThread{
            var builder = StringBuffer(binding.logTv.text)
            builder.append("\n")
            builder.append(content)
            binding.logTv.text = builder.toString()
        }
    }

    private fun init() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(
                arrayOf<String>(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE), 111);
        }
        registerUsbReceiver();
        redUDiskDeviceList();
    }

    private fun redUDiskDeviceList() {
        //设备管理器
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        //获取U盘存储设备
        storageDevices = UsbMassStorageDevice.getMassStorageDevices(this)
        val pendingIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSIONS), 0)
        //一般手机只有1个OTG插口
        if (storageDevices?.size!! <= 0) {
            return;
        }
        val device = storageDevices!![0]
        //读取设备是否有权限
        if (usbManager.hasPermission(device.getUsbDevice())) {
            readDevice(device);
        } else {
            //没有权限，进行申请
            usbManager.requestPermission(device.getUsbDevice(), pendingIntent);
        }
    }

    private fun getUsbMass(usbDevice: UsbDevice?): UsbMassStorageDevice?{
        for (device in storageDevices!!){
            if (usbDevice?.equals(device.usbDevice) == true){
                return device
            }
        }
        return null
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter(ACTION_USB_PERMISSIONS)
        filter.addAction("android.hardware.usb.action.USB_STATE");
        filter.addAction("android.hardware.usb.action.USB_DEVICE_ATTACHED");
        filter.addAction("android.hardware.usb.action.USB_DEVICE_DETACHED");
        registerReceiver(mUsbReceiver, filter);
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 111) {
            registerUsbReceiver();
            redUDiskDeviceList();
        }
    }



}

