package dev.pocket.agent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class SmsReceiptReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent) {
        Integer delivery=null;
        if("delivered".equals(intent.getStringExtra("stage"))) {
            byte[] pdu=intent.getByteArrayExtra("pdu");String format=intent.getStringExtra("format");
            if(pdu!=null && ("3gpp".equals(format)||"3gpp2".equals(format))) {
                try {android.telephony.SmsMessage report=android.telephony.SmsMessage.createFromPdu(pdu,format);if(report!=null && report.isStatusReportMessage() && report.getStatus()>=0) delivery=report.getStatus();}catch(RuntimeException malformed) {}
            }
        }
        // A broadcast RESULT_OK alone does not establish delivery: inspect its status-report PDU.
        try(OperationStore store=new OperationStore(context)) {store.receipt(intent.getStringExtra("operation"),intent.getStringExtra("stage"),intent.getIntExtra("part",-1),getResultCode(),delivery);}
    }
}
