package com.shilapi.xcertplay.transport;
import android.hardware.usb.*;
import android.os.Build;
import android.util.Log;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
/** API18 reads on direct buffers keep blocking USB waits outside Dalvik JNI critical sections. */
public final class H52UsbReadPump {
 private static final IdentityHashMap<UsbDeviceConnection,ConnectionPumps> pumps=new IdentityHashMap<>();
 private static final class ConnectionPumps {
  final HashMap<Integer,Pump> endpoints=new HashMap<>();
  boolean closing;
 }
 public static int read(UsbDeviceConnection c, UsbEndpoint ep, byte[] target,int offset,int length,int timeout) {
  if(Build.VERSION.SDK_INT>=26) return c.bulkTransfer(ep,target,offset,length,timeout);
  Pump p;
  synchronized(pumps) {
   ConnectionPumps state=pumps.get(c);
   if(state!=null&&state.closing)throw new IllegalStateException("USB connection is closing");
   if(c.getFileDescriptor()<0)throw new IllegalStateException("USB connection is closed");
   if(state==null){state=new ConnectionPumps();pumps.put(c,state);}
   p=state.endpoints.get(ep.getAddress());
   if(p==null){p=new Pump(c,ep);state.endpoints.put(ep.getAddress(),p);p.start();}
  }
  return p.read(target,offset,length,timeout);
 }
 /** Off-main owner teardown. Keep the connection alive until every submitted request is reaped. */
 public static void close(UsbDeviceConnection connection,UsbInterface... claimedInterfaces) {
  ArrayList<Pump> readers;
  synchronized(pumps) {
   ConnectionPumps state=pumps.get(connection);
   if(state==null){state=new ConnectionPumps();pumps.put(connection,state);}
   state.closing=true;
   readers=new ArrayList<>(state.endpoints.values());
  }
  // Stop/queue share a lock, but requestWait and join must never hold that lock.
  for(Pump reader:readers)reader.stopReads();
  for(UsbInterface claimedInterface:claimedInterfaces){
   boolean releaseReported=connection.releaseInterface(claimedInterface);
   Log.i("xcertplay-usb","USB release iface="+claimedInterface.getId()+" reported="+releaseReported+" readers="+readers.size());
  }
  for(Pump reader:readers) {
   try{reader.join();}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException("Interrupted draining USB read request",error);}
   if(reader.requestQueued)throw new IllegalStateException("USB read request has not been reaped",reader.failure);
  }
  // API18 releaseInterface may report false even after releasing. Actual dequeue is the gate.
  connection.close();
  synchronized(pumps){pumps.remove(connection);}
  Log.i("xcertplay-usb","USB close completed readers="+readers.size());
 }
 private static final class Pump extends Thread {
  final UsbDeviceConnection connection; final UsbEndpoint endpoint;
  final ArrayBlockingQueue<UsbReadChunkPool.Chunk> packets=new ArrayBlockingQueue<>(64);
  final UsbReadChunkPool chunkPool=new UsbReadChunkPool();
  final String devicePath; volatile boolean ended; volatile boolean stopping; volatile RuntimeException failure;
  final Object requestLock=new Object();
  final Object readLock=new Object();
  UsbRequest request; ByteBuffer buffer; boolean requestQueued;
  UsbReadChunkPool.Chunk remaining; int position;
  Pump(UsbDeviceConnection c,UsbEndpoint ep){
   super("h52-usb-read-"+ep.getAddress());setDaemon(true);connection=c;endpoint=ep;
   String path=null;try{String candidate=new File("/proc/self/fd/"+c.getFileDescriptor()).getCanonicalPath();if(candidate.startsWith("/dev/bus/usb/"))path=candidate;}catch(Exception ignored){}
   devicePath=path;
  }
  void checkDevice(){
   if(stopping)throw new IllegalStateException("USB read request is closing");
   if(connection.getFileDescriptor()<0 || (devicePath!=null&&!new File(devicePath).exists()))throw new IllegalStateException("USB device detached");
   if(failure!=null)throw failure;
   if(ended&&packets.isEmpty()&&remaining==null)throw new IllegalStateException("USB read request ended");
  }
  void stopReads(){synchronized(requestLock){stopping=true;}}
  int read(byte[] target,int offset,int length,int timeout){synchronized(readLock){
   if(length<=0)return 0;
   checkDevice();
   if(remaining==null){try{remaining=packets.poll(Math.max(1,timeout),TimeUnit.MILLISECONDS);position=0;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("USB read interrupted",e);}}
   checkDevice(); if(remaining==null)return -1;
   int count=Math.min(length,remaining.length-position);System.arraycopy(remaining.bytes,position,target,offset,count);position+=count;
   // Return ownership only after the final copy, including when callers split a USB request.
   if(position==remaining.length){chunkPool.release(remaining);remaining=null;position=0;}return count;
  }}
  public void run(){
   try{
    synchronized(requestLock){
     if(stopping)return;
     request=new UsbRequest();
     if(!request.initialize(connection,endpoint))throw new IllegalStateException("USB request initialize failed");
     buffer=ByteBuffer.allocateDirect(16384);
    }
    while(true){
     synchronized(requestLock){
      if(stopping)return;
      checkDevice();buffer.clear();
      if(!request.queue(buffer,buffer.capacity()))throw new IllegalStateException("USB request queue failed");
      requestQueued=true;
     }
     UsbRequest complete=connection.requestWait();
     synchronized(requestLock){
      if(complete!=request)throw new IllegalStateException("USB device detached or request replaced");
      requestQueued=false;
      if(stopping)return;
     }
     int count=buffer.position(); if(count<=0){checkDevice();continue;}
     buffer.flip();UsbReadChunkPool.Chunk data=chunkPool.copyFrom(buffer,count);while(!packets.offer(data,100,TimeUnit.MILLISECONDS))checkDevice();
    }
   }catch(InterruptedException e){Thread.currentThread().interrupt();failure=new IllegalStateException("USB pump interrupted",e);}
   catch(RuntimeException e){failure=e;}
   finally{
    ended=true;
    synchronized(requestLock){
     // A failed reap is not evidence that the driver released the request or its direct buffer.
     if(!requestQueued){if(request!=null)request.close();request=null;buffer=null;}
    }
    synchronized(pumps){
     ConnectionPumps state=pumps.get(connection);
     if(state!=null&&!state.closing&&!requestQueued&&state.endpoints.get(endpoint.getAddress())==this){
      state.endpoints.remove(endpoint.getAddress());if(state.endpoints.isEmpty())pumps.remove(connection);
     }
    }
   }
  }
 }
}
