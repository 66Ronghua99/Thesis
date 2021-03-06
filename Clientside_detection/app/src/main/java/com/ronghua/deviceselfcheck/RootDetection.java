package com.ronghua.deviceselfcheck;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.util.Log;
import android.widget.Toast;

import com.ronghua.selfcheck.IIsolatedProcess;
import com.ronghua.deviceselfcheck.Utils.Const;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class RootDetection {
    @SuppressLint("StaticFieldLeak") //This is an application Context
    private static RootDetection instance;
    public static String TAG = "RootDetection";
    private Context mContext;
    private IIsolatedProcess service;
    private HandlerThread handlerThread;
    private Handler handler;
    private List<String> rootTraitsList = new ArrayList<>();

    public List<String> getRootTraitsList() {
        return rootTraitsList;
    }

    public static RootDetection getInstance(Context context, IIsolatedProcess service){
        if(instance == null){
            instance = new RootDetection(context, service);
        }
        return getInstance();
    }

    public static RootDetection getInstance(){
        return instance;
    }

    private RootDetection(Context context, IIsolatedProcess service){
        this.mContext = context;
        this.service = service;
        if(handlerThread == null)
            initThread();
    }

    private void initThread(){
        handlerThread = new HandlerThread("selfcheck-bg");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
    }

    public void isRooted(){
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    rootTraitsList.clear();
                    boolean isRooted = checkRooted();
                    if(isRooted){
                        Toast.makeText(mContext, "Device is rooted", Toast.LENGTH_LONG).show();
                    }else{
                        Toast.makeText(mContext, "Device is not rooted", Toast.LENGTH_LONG).show();
                    }
                    Intent intent = new Intent(mContext, DetectResultActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.putExtra("com.ronghua.deviceselfcheck", "root");
                    mContext.startActivity(intent);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    private boolean checkRooted() throws RemoteException {
        boolean result = isSuExists();
                result |= suFileDetection();
                result |= detectRootNative();
                result |= buildTagDetection();
                result |= mountPathsDetection();
                result |= rootAppDetection();
                result |= dangerousAppDetection();
                result |= rootCloakingAppDetection();
                result |= detectMagiskHide();
        return result;
    }

    private boolean detectRootNative() {
        boolean isSuDetected = Native.detectRootNative();
        if(isSuDetected){
            rootTraitsList.add("SU file detected with native code");
        }
        return isSuDetected;
    }

    public boolean rootAppDetection(){
        return arePkgsInstalled(Const.knownRootAppsPackages);
    }

    public boolean dangerousAppDetection(){
        return arePkgsInstalled(Const.knownDangerousAppsPackages);
    }

    public boolean rootCloakingAppDetection(){
        return arePkgsInstalled(Const.knownRootCloakingPackages);
    }

    public boolean buildTagDetection(){
        String buildTags = android.os.Build.TAGS;
        Log.i(TAG, "Build tags: "+ buildTags);
        if(buildTags != null && buildTags.contains("test-keys")){
            rootTraitsList.add("BuildTags contain: test-keys");
            return true;
        }
        return false;
    }

    //Haven't considered SDK version difference
    public boolean mountPathsDetection(){
        try {
            FileReader fr = new FileReader("/proc/self/mounts");
            BufferedReader br = new BufferedReader(fr);
            String line = "";
            while((line = br.readLine()) != null){
                String[] args = line.split(" ");
                for(String path:Const.pathsThatShouldNotBeWritable){
                    if(args[1].contains(path) && args[3].split(",")[0].equals("rw")){
                        rootTraitsList.add("Mounted Path '" + args[1] + "' is detected writable");
                        return true;
                    }
                }
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    public boolean arePkgsInstalled(String[] suspects){
        PackageManager pm = mContext.getPackageManager();
        for(String pkg: suspects){
            try {
                pm.getPackageInfo(pkg, 0);
                Log.i(TAG, "Root related app "+ pkg + " is detected!");
                rootTraitsList.add("Root related app '" + pkg + "' is detected");
                return true;
            } catch (PackageManager.NameNotFoundException e) {
                //Do nothing
            }
        }
        return false;
    }

    public boolean suFileDetection(){
        return filePathDetection("su")||filePathDetection("magisk")
                ||filePathDetection("busybox") || Native.isSuExist();
    }

    public boolean filePathDetection(String filename){
        boolean isSuDetected = false;
        String[] suPaths = Const.getPaths();
        for(String path: suPaths) {
            File file = new File(path, filename);
            if(file.exists()){
                isSuDetected = true;
                Log.i(TAG, "file detected: " + path + filename);
                rootTraitsList.add(filename + " file detected in path: " + path);
                break;
            }
        }
        return isSuDetected;
    }

    public boolean isSuExists(){
        boolean isSuExist = false;
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("which su\n");
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = br.readLine();
            if(line != null){
                isSuExist = true;
                rootTraitsList.add("'which su' finds su file in path: " + line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }finally {
            if(p != null)
                p.destroy();
        }
        return isSuExist;
    }

    public boolean detectMagiskHide() throws RemoteException {
            boolean result = service.detectMagiskHide();
            if(result){
                rootTraitsList.add("Magisk is detected with IsolatedProcess detection");
            }
            return result;
    }
}
