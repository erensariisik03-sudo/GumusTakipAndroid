package com.eren.gumustakip;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/** Restarts tracking after reboot/update only when the user previously enabled it. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        if (!context.getSharedPreferences("gumus", Context.MODE_PRIVATE).getBoolean("tracking_enabled", false)) return;
        Intent service = new Intent(context, PriceTrackingService.class);
        service.setAction(PriceTrackingService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(service);
            else context.startService(service);
        } catch (Exception e) {
            Log.e("GumusTakip", "Takip servisi yeniden başlatılamadı", e);
        }
    }
}
