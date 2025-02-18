/*
 * This file is part of PCAPdroid.
 *
 * PCAPdroid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PCAPdroid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PCAPdroid.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright 2020-21 - Emanuele Faranda
 */

package com.emanuelef.remote_capture.activities;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.ConnectionsRegister;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.fragments.ConnectionOverview;
import com.emanuelef.remote_capture.fragments.ConnectionPayload;
import com.emanuelef.remote_capture.interfaces.ConnectionsListener;
import com.emanuelef.remote_capture.model.ConnectionDescriptor;
import com.emanuelef.remote_capture.model.PayloadChunk;
import com.google.android.material.tabs.TabLayoutMediator;

import java.util.ArrayList;

public class ConnectionDetailsActivity extends BaseActivity implements ConnectionsListener {
    private static final String TAG = "ConnectionDetails";
    public static final String CONN_ID_KEY = "conn_id";
    private static final int MAX_CHUNKS_TO_CHECK = 10;
    private ConnectionDescriptor mConn;
    private ViewPager2 mPager;
    private StateAdapter mPagerAdapter;
    private Handler mHandler;
    private int mConnPos;
    private int mCurChunks;
    private boolean mListenerSet;
    private boolean mHasPayload;
    private boolean mHasHttpTab;
    private boolean mHasWsTab;
    private final ArrayList<ConnUpdateListener> mListeners = new ArrayList<>();

    private static final int POS_OVERVIEW = 0;
    private static final int POS_WEBSOCKET = 1;
    private static final int POS_HTTP = 2;
    private static final int POS_RAW_PAYLOAD = 3;

    public interface ConnUpdateListener {
        void connectionUpdated();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.connection_details);
        displayBackAction();
        setContentView(R.layout.activity_connection_details);

        int incr_id = getIntent().getIntExtra(CONN_ID_KEY, -1);
        if(incr_id != -1) {
            ConnectionsRegister reg = CaptureService.getConnsRegister();
            if(reg != null)
                mConn = reg.getConnById(incr_id);
        }

        if(mConn == null) {
            Log.w(TAG, "Connection with ID " + incr_id + " not found");
            finish();
            return;
        }

        mHandler = new Handler(Looper.getMainLooper());
        mConnPos = -1;

        mPager = findViewById(R.id.pager);
        setupTabs();
    }

    @Override
    public void onResume() {
        super.onResume();
        mConnPos = -1;

        // Closed connections won't be updated
        if(mConn.status < ConnectionDescriptor.CONN_STATUS_CLOSED)
            registerConnsListener();
    }

    @Override
    public void onPause() {
        super.onPause();

        unregisterConnsListener();
    }

    private void setupTabs() {
        mPagerAdapter = new StateAdapter(this);
        mPager.setAdapter(mPagerAdapter);

        new TabLayoutMediator(findViewById(R.id.tablayout), mPager, (tab, position) ->
                tab.setText(getString(mPagerAdapter.getPageTitle(position)))
        ).attach();

        mCurChunks = 0;
        recheckTabs();
    }

    private class StateAdapter extends FragmentStateAdapter {
        StateAdapter(final FragmentActivity fa) { super(fa); }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            //Log.d(TAG, "createFragment");
            int pos = getVisibleTabsPositions()[position];

            switch (pos) {
                case POS_WEBSOCKET:
                    return ConnectionPayload.newInstance(PayloadChunk.ChunkType.WEBSOCKET);
                case POS_HTTP:
                    return ConnectionPayload.newInstance(PayloadChunk.ChunkType.HTTP);
                case POS_RAW_PAYLOAD:
                    return ConnectionPayload.newInstance(PayloadChunk.ChunkType.RAW);
                case POS_OVERVIEW:
                default:
                    return new ConnectionOverview();
            }
        }

        @Override
        public int getItemCount() {  return 1 + (mHasPayload ? 1 : 0) + (mHasHttpTab ? 1 : 0) + (mHasWsTab ? 1 : 0);  }

        public int getPageTitle(final int position) {
            int pos = getVisibleTabsPositions()[position];

            switch (pos) {
                case POS_WEBSOCKET:
                    return R.string.websocket;
                case POS_HTTP:
                    return R.string.http;
                case POS_RAW_PAYLOAD:
                    return R.string.payload;
                case POS_OVERVIEW:
                default:
                    return R.string.overview;
            }
        }

        private int[] getVisibleTabsPositions() {
            int[] visible = new int[getItemCount()];
            int i = 0;

            visible[i++] = POS_OVERVIEW;

            if(mHasWsTab)
                visible[i++] = POS_WEBSOCKET;
            if(mHasHttpTab)
                visible[i++] = POS_HTTP;
            if(mHasPayload)
                visible[i] = POS_RAW_PAYLOAD;

            return visible;
        }
    }

    private void registerConnsListener() {
        ConnectionsRegister reg = CaptureService.getConnsRegister();

        if((reg != null) && !mListenerSet) {
            mConnPos = reg.getConnPositionById(mConn.incr_id);

            if((mConnPos != -1) && (mConn.status < ConnectionDescriptor.CONN_STATUS_CLOSED)) {
                Log.d(TAG, "Adding connections listener");
                reg.addListener(this);
                mListenerSet = true;
            }
        }

        dispatchConnUpdate();
    }

    private void unregisterConnsListener() {
        if(mListenerSet) {
            ConnectionsRegister reg = CaptureService.getConnsRegister();

            if(reg != null) {
                Log.d(TAG, "Removing connections listener");
                reg.removeListener(this);
            }

            mListenerSet = false;
        }

        mConnPos = -1;
    }

    @Override
    public void connectionsChanges(int num_connetions) {}

    @Override
    public void connectionsAdded(int start, ConnectionDescriptor []conns) {}

    @Override
    public void connectionsRemoved(int start, ConnectionDescriptor []conns) {}

    @Override
    public void connectionsUpdated(int[] positions) {
        ConnectionsRegister reg = CaptureService.getConnsRegister();

        if((reg == null) || (mConnPos < 0))
            return;

        for(int pos : positions) {
            if(pos == mConnPos) {
                ConnectionDescriptor conn = reg.getConn(pos);

                // Double check the incr_id
                if((conn != null) && (conn.incr_id == mConn.incr_id))
                    mHandler.post(this::dispatchConnUpdate);
                else
                    unregisterConnsListener();

                break;
            }
        }
    }

    public void addConnUpdateListener(ConnUpdateListener listener) {
        mListeners.add(listener);
    }

    public void removeConnUpdateListener(ConnUpdateListener listener) {
        mListeners.remove(listener);
    }

    @SuppressLint("NotifyDataSetChanged")
    private void recheckTabs() {
        if(mHasHttpTab && mHasWsTab)
            return;

        int max_check = Math.min(mConn.getNumPayloadChunks(), MAX_CHUNKS_TO_CHECK);
        boolean changed = false;

        if(!mHasPayload && (max_check > 0)) {
            mHasPayload = true;
            changed = true;
        }

        for(int i=mCurChunks; i<max_check; i++) {
            PayloadChunk chunk = mConn.getPayloadChunk(i);

            if(!mHasHttpTab && (chunk.type == PayloadChunk.ChunkType.HTTP)) {
                mHasHttpTab = true;
                changed = true;
            } else if (!mHasWsTab && (chunk.type == PayloadChunk.ChunkType.WEBSOCKET)) {
                mHasWsTab = true;
                changed = true;
            }
        }

        if(changed)
            mPagerAdapter.notifyDataSetChanged();

        mCurChunks = max_check;
    }

    private void dispatchConnUpdate() {
        for(ConnUpdateListener listener: mListeners)
            listener.connectionUpdated();

        if((mCurChunks < MAX_CHUNKS_TO_CHECK) && (mConn.getNumPayloadChunks() > mCurChunks))
            recheckTabs();

        if(mConn.status >= ConnectionDescriptor.CONN_STATUS_CLOSED)
            unregisterConnsListener();
    }

    public ConnectionDescriptor getConn() { return mConn; }
}
