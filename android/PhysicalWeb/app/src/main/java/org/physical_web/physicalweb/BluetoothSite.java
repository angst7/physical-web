/*
 * Copyright 2016 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.physical_web.physicalweb;

import android.app.Activity;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothProfile;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.Intent;
import android.net.Uri;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.support.v4.content.FileProvider;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * This class is used to connect to a ble gatt service and download a web page.
 */
public class BluetoothSite extends BluetoothGattCallback {

  private static final String TAG = BluetoothSite.class.getSimpleName();
  private static final UUID SERVICE_UUID = UUID.fromString("ae5946d4-e587-4ba8-b6a5-a97cca6affd3");
  private static final UUID CHARACTERISTIC_WEBPAGE_UUID = UUID.fromString(
      "d1a517f0-2499-46ca-9ccc-809bc1c966fa");
  private static final String WEBPAGE_CHAR_BASE_UUID = "d1a50000-2499-46ca-9ccc-809bc1c966fa";
  private static final int WEBPAGE_CHAR_UUID = 0x17f0;
  private static final int MAX_PAGE_CHARACTERISTICS = 20;   // Pages limited to 20 500 byte characteristics
  private Activity activity;
  private BluetoothGatt mBluetoothGatt;
  private BluetoothGattCharacteristic characteristic;
  private ProgressDialog progress;
  //private int transferRate = 20;
  private int pageCharacteristics = 0;   // # of characteristics for the page we're reading
  private int characteristicsRead = 0;
  private StringBuilder html;

  public BluetoothSite(Activity activity) {
    this.activity = activity;
  }

  /**
   * Connects to the Gatt service of the device to download a web page and displays a progress bar
   * for the title.
   * @param deviceAddress The mac address of the bar
   * @param title The title of the web page being downloaded
   */
  public void connect(String deviceAddress, String title) {
    String progressTitle = activity.getString(R.string.page_loading_title) + " " + title;
    progress = new ProgressDialog(activity);
    progress.setCancelable(true);
    progress.setOnCancelListener(new OnCancelListener() {
      @Override
      public void onCancel(DialogInterface dialogInterface) {
        Log.i(TAG, "Dialog box canceled");
        close();
      }
    });
    progress.setTitle(progressTitle);
    progress.setMessage(activity.getString(R.string.page_loading_message));
    progress.show();
    BluetoothAdapter.getDefaultAdapter().getRemoteDevice(deviceAddress)
        .connectGatt(activity, false, this);
  }

  @Override
  public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic,
      int status) {
    if ((status == BluetoothGatt.GATT_SUCCESS) && (pageCharacteristics == 0)) {  // First char holds page char count
      pageCharacteristics = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0);
      // make sure the webpage is not empty, and less than the maximum size
      if ((pageCharacteristics > 0) && (pageCharacteristics < MAX_PAGE_CHARACTERISTICS)) {
        UUID nextPageCharUUID = getNextUUID();

        characteristic = gatt.getService(SERVICE_UUID).getCharacteristic(nextPageCharUUID);
        gatt.readCharacteristic(characteristic);
      } else {
        Log.i(TAG, "onCharacteristicRead returned invalid number of page Characteristics");
        close();
        progress.dismiss();
      }
    } else if (status == BluetoothGatt.GATT_SUCCESS) {
        characteristicsRead++;
        Log.i(TAG, "onCharacteristicRead successful: page char #" + characteristicsRead);
        html.append(new String(characteristic.getValue()));

        if (characteristicsRead < pageCharacteristics) {   // Keep reading
          UUID nextPageCharUUID = getNextUUID();

          characteristic = gatt.getService(SERVICE_UUID).getCharacteristic(nextPageCharUUID);
          gatt.readCharacteristic(characteristic);
        } else {  // All done, cleanup and show the page
          progress.dismiss();
          gatt.close();
          File websiteDir = new File(activity.getFilesDir(), "Websites");
          websiteDir.mkdir();
          File file = new File(websiteDir, "website.html");
          writeToFile(file);
          if (file != null) {
            openInChrome(file);
          }
        }
    } else {
      Log.i(TAG, "onCharacteristicRead unsuccessful: " + status);
      close();
      progress.dismiss();
    }

  }

  @Override
  public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
    if (newState == BluetoothProfile.STATE_CONNECTED) {
      Log.i(TAG, "Connected to GATT server");
      mBluetoothGatt = gatt;
      html = new StringBuilder("");
      if (VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP) {
        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
        gatt.requestMtu(505);
      } else {
        gatt.discoverServices();
      }
    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
      Log.i(TAG, "Disconnected to GATT server");
    }
  }


  @Override
  public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
    Log.i(TAG, "MTU changed to " + mtu);
    //transferRate = mtu - 5;
    gatt.discoverServices();
  }

  @Override
  public void onServicesDiscovered(BluetoothGatt gatt, int status) {
    Log.i(TAG, "Services Discovered");
    if (status == BluetoothGatt.GATT_SUCCESS) {
      characteristic = gatt.getService(SERVICE_UUID).getCharacteristic(CHARACTERISTIC_WEBPAGE_UUID);
      gatt.readCharacteristic(characteristic);
    }
  }

  private void close() {
    if (mBluetoothGatt == null) {
      return;
    }
    mBluetoothGatt.close();
    mBluetoothGatt = null;
  }

  // Constructs a Characteristic UUID from the current read position and the base UUID
  private UUID getNextUUID() {
      String nextPageCharUUIDString = WEBPAGE_CHAR_BASE_UUID.substring(0,4)+
             String.format("%04x",WEBPAGE_CHAR_UUID+1+ characteristicsRead)+
             WEBPAGE_CHAR_BASE_UUID.substring(8);
      return UUID.fromString(nextPageCharUUIDString);
  }

  private void openInChrome(File file) {
    Intent intent = new Intent(Intent.ACTION_VIEW);
    Uri contentUri = new FileProvider()
        .getUriForFile(activity, "org.physical_web.fileprovider", file);
    activity.grantUriPermission("com.android.chrome", contentUri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION);
    intent.setDataAndType(contentUri, "text/html");
    intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    activity.startActivity(intent);
  }

  private void writeToFile(File file) {
    FileOutputStream outputStream;
    try {
      outputStream = new FileOutputStream(file);
      try {
        outputStream.write(html.toString().getBytes());
      } catch (IOException e) {
        Log.e(TAG, "Failed to write to file");
      } finally {
        outputStream.close();
      }
    } catch (FileNotFoundException e) {
      Log.e(TAG, "File not found: " + file.getName());
    } catch (IOException e) { }
  }

}
