package edu.buffalo.cse.cse486586.simpledht;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.EditText;
import android.widget.TextView;

public class OnInsertClickListener implements OnClickListener {
    private static final String KEY_FIELD = "key";
    private static final String VALUE_FIELD = "value";

    private final EditText key;
    private final EditText value;
    private final ContentResolver mContentResolver;
    private final Uri mUri;

    public OnInsertClickListener(EditText key, EditText value, ContentResolver _cr) {
        this.key = key;
        this.value = value;
        mContentResolver = _cr;
        mUri = buildUri("content", "edu.buffalo.cse.cse486586.simpledht.provider");
    }

    private Uri buildUri(String scheme, String authority) {
        Uri.Builder uriBuilder = new Uri.Builder();
        uriBuilder.authority(authority);
        uriBuilder.scheme(scheme);
        return uriBuilder.build();
    }

    @Override
    public void onClick(View v) {
        ContentValues cv = new ContentValues();
        cv.put(KEY_FIELD, key.getText().toString());
        cv.put(VALUE_FIELD, value.getText().toString());
        mContentResolver.insert(mUri, cv);
        key.setText("");
        value.setText("");
    }
}

