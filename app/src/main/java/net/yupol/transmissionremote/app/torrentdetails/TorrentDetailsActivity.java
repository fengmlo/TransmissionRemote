package net.yupol.transmissionremote.app.torrentdetails;

import android.content.SharedPreferences;
import android.databinding.DataBindingUtil;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.view.LayoutInflaterCompat;
import android.support.v4.view.ViewPager;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.ActionBar;
import android.util.Log;
import android.util.SparseArray;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import com.mikepenz.iconics.context.IconicsLayoutInflater2;
import com.octo.android.robospice.persistence.exception.SpiceException;
import com.octo.android.robospice.request.listener.RequestListener;

import net.yupol.transmissionremote.app.R;
import net.yupol.transmissionremote.app.TransmissionRemote;
import net.yupol.transmissionremote.app.databinding.TorrentDetailsLayoutBinding;
import net.yupol.transmissionremote.app.model.json.Torrent;
import net.yupol.transmissionremote.app.model.json.TorrentInfo;
import net.yupol.transmissionremote.app.model.json.Torrents;
import net.yupol.transmissionremote.app.torrentlist.ChooseLocationDialogFragment;
import net.yupol.transmissionremote.app.torrentlist.RemoveTorrentsDialogFragment;
import net.yupol.transmissionremote.app.torrentlist.RenameDialogFragment;
import net.yupol.transmissionremote.app.transport.BaseSpiceActivity;
import net.yupol.transmissionremote.app.transport.request.ReannounceTorrentRequest;
import net.yupol.transmissionremote.app.transport.request.RenameRequest;
import net.yupol.transmissionremote.app.transport.request.SetLocationRequest;
import net.yupol.transmissionremote.app.transport.request.StartTorrentRequest;
import net.yupol.transmissionremote.app.transport.request.StopTorrentRequest;
import net.yupol.transmissionremote.app.transport.request.TorrentGetRequest;
import net.yupol.transmissionremote.app.transport.request.TorrentInfoGetRequest;
import net.yupol.transmissionremote.app.transport.request.TorrentRemoveRequest;
import net.yupol.transmissionremote.app.transport.request.TorrentSetRequest;
import net.yupol.transmissionremote.app.transport.request.TrackerAddRequest;
import net.yupol.transmissionremote.app.transport.request.VerifyTorrentRequest;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class TorrentDetailsActivity extends BaseSpiceActivity implements SaveChangesDialogFragment.SaveDiscardListener,
        RemoveTorrentsDialogFragment.OnRemoveTorrentSelectionListener, ChooseLocationDialogFragment.OnLocationSelectedListener,
        TorrentInfoUpdater.OnTorrentInfoUpdatedListener, SwipeRefreshLayout.OnRefreshListener, RenameDialogFragment.OnNameSelectedListener {

    private static final String TAG = TorrentDetailsActivity.class.getSimpleName();

    public static final String EXTRA_TORRENT = "extra_key_torrent";

    private static final String TAG_SAVE_CHANGES_DIALOG = "tag_save_changes_dialog";
    private static final String TAG_CHOOSE_LOCATION_DIALOG = "tag_choose_location_dialog";

    private static final String KEY_OPTIONS_CHANGE_REQUEST = "key_options_request";
    private static final String KEY_LAST_PAGE_POSITION = "key_last_position";

    private static final String RENAME_TORRENT_FRAGMENT_TAG = "rename_torrent_fragment_tag";

    private Torrent torrent;
    private TorrentInfo torrentInfo;
    private SparseArray<TorrentSetRequest> saveChangesRequests = new SparseArray<>();
    private List<OnDataAvailableListener<TorrentInfo>> torrentInfoListeners = new LinkedList<>();
    private List<OnActivityExitingListener<TorrentSetRequest.Builder>> activityExitingListeners = new LinkedList<>();
    private TorrentDetailsPagerAdapter pagerAdapter;
    private MenuItem setLocationMenuItem;
    private TorrentInfoUpdater torrentInfoUpdater;
    private TorrentDetailsLayoutBinding binding;
    private SharedPreferences sharedPreferences;
    private OkHttpClient okHttpClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        LayoutInflaterCompat.setFactory2(getLayoutInflater(), new IconicsLayoutInflater2(getDelegate()));
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.torrent_details_layout);

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(TorrentDetailsActivity.this);

        torrent = getIntent().getParcelableExtra(EXTRA_TORRENT);
        setupPager();

        binding.pager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                sharedPreferences.edit().putInt(KEY_LAST_PAGE_POSITION, position).apply();
            }

            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {}

            @Override
            public void onPageScrollStateChanged(int state) {}
        });

        if (setLocationMenuItem != null) {
            setLocationMenuItem.setEnabled(torrentInfo != null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        torrentInfoUpdater.start(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        torrentInfoUpdater.stop();
    }

    @Override
    public void onTorrentInfoUpdated(TorrentInfo torrentInfo) {
        // TorrentInfo may be empty if torrent is removed after request was sent.
        // Show content only if TorrentInfo contain files data.
        if (torrentInfo.getFiles() != null) {
            TorrentDetailsActivity.this.torrentInfo = torrentInfo;
            invalidateOptionsMenu();
            pagerAdapter.setTorrentInfo(torrentInfo);
            notifyTorrentInfoListeners();
            if (setLocationMenuItem != null) {
                setLocationMenuItem.setEnabled(true);
            }
        } else {
            Log.e(TAG, "Empty TorrentInfo");
            showErrorAndExit();
        }
    }

    private void showErrorAndExit() {
        Toast.makeText(TorrentDetailsActivity.this, R.string.error_retrieve_torrent_details, Toast.LENGTH_LONG).show();
        onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putSparseParcelableArray(KEY_OPTIONS_CHANGE_REQUEST, saveChangesRequests);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        saveChangesRequests = savedInstanceState.getSparseParcelableArray(KEY_OPTIONS_CHANGE_REQUEST);
        super.onRestoreInstanceState(savedInstanceState);
    }

    public TorrentInfo getTorrentInfo() {
        return torrentInfo;
    }

    public void addTorrentInfoListener(OnDataAvailableListener<TorrentInfo> listener) {
        if (!torrentInfoListeners.contains(listener)) {
            torrentInfoListeners.add(listener);
        }
    }

    public void removeTorrentInfoListener(OnDataAvailableListener<TorrentInfo> listener) {
        torrentInfoListeners.remove(listener);
    }

    private void notifyTorrentInfoListeners() {
        for (OnDataAvailableListener<TorrentInfo> listener : torrentInfoListeners) {
            listener.onDataAvailable(torrentInfo);
        }
    }

    public void addOnActivityExitingListener(OnActivityExitingListener<TorrentSetRequest.Builder> listener) {
        if (!activityExitingListeners.contains(listener)) {
            activityExitingListeners.add(listener);
        }
    }

    public void removeOnActivityExitingListener(OnActivityExitingListener<TorrentSetRequest.Builder> listener) {
        activityExitingListeners.remove(listener);
    }

    public void addSaveChangesRequest(TorrentSetRequest.Builder requestBuilder) {
        saveChangesRequests.put(requestBuilder.getTorrentId(), requestBuilder.build());
    }

    private void setupPager() {
        pagerAdapter = new TorrentDetailsPagerAdapter(this, getSupportFragmentManager(), torrent);

        boolean restartUpdater = false;
        if (torrentInfoUpdater != null) {
            torrentInfoUpdater.stop();
            restartUpdater = true;
        }
        torrentInfoUpdater = new TorrentInfoUpdater(getTransportManager(), torrent.getId(),
                1000 * TransmissionRemote.getInstance().getUpdateInterval());
        if (restartUpdater) torrentInfoUpdater.start(this);

        if (torrentInfo != null) pagerAdapter.setTorrentInfo(torrentInfo);

        setupActionBar();

        binding.pager.setAdapter(pagerAdapter);

        int lastPagePosition = sharedPreferences.getInt(KEY_LAST_PAGE_POSITION, 0);
        binding.pager.setCurrentItem(lastPagePosition < pagerAdapter.getCount() ? lastPagePosition : 0);
    }

    private void setupActionBar() {
        binding.toolbar.setSubtitle(torrent.getName());
        setSupportActionBar(binding.toolbar);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        return handleExit();
    }

    @Override
    public void onBackPressed() {
        boolean handled = handleBackPressByFragments();
        if (handled) return;
        handleExit();
    }

    private boolean handleExit() {
        if (torrentInfo == null) {
            finish();
            return true;
        }

        for (OnActivityExitingListener<TorrentSetRequest.Builder> listener : activityExitingListeners) {
            TorrentSetRequest.Builder saveChangesRequestBuilder = listener.onActivityExiting();
            if (saveChangesRequestBuilder != null && saveChangesRequestBuilder.isChanged()) {
                addSaveChangesRequest(saveChangesRequestBuilder);
            }
        }

        if (saveChangesRequests.size() > 0) {
            new SaveChangesDialogFragment().show(getFragmentManager(), TAG_SAVE_CHANGES_DIALOG);
        } else {
            finish();
            return true;
        }
        return false;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.torrent_details_actions_menu, menu);
        setLocationMenuItem = menu.findItem(R.id.action_set_location);
        setLocationMenuItem.setEnabled(torrentInfo != null);
        if (torrentInfo != null && torrentInfo.isPrivate()) {
            menu.findItem(R.id.action_add_all_trackers).setVisible(false);
            menu.findItem(R.id.action_add_best_trackers).setVisible(false);
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_remove_torrents:
                RemoveTorrentsDialogFragment.newInstance(torrent.getId())
                        .show(getSupportFragmentManager(), RemoveTorrentsDialogFragment.TAG_REMOVE_TORRENTS_DIALOG);
                return true;
            case R.id.action_pause:
                getTransportManager().doRequest(new StopTorrentRequest(torrent.getId()), null);
                return true;
            case R.id.action_start:
                getTransportManager().doRequest(new StartTorrentRequest(torrent.getId()), null);
                return true;
            case R.id.action_start_now:
                getTransportManager().doRequest(new StartTorrentRequest(new int[] { torrent.getId() }, true), null);
                return true;
            case R.id.action_rename:
                RenameDialogFragment dialogFragment = RenameDialogFragment.newInstance(torrent.getId(), torrent.getName(), torrent.getName());
                dialogFragment.show(getSupportFragmentManager(), RENAME_TORRENT_FRAGMENT_TAG);
                return true;
            case R.id.action_set_location:
                ChooseLocationDialogFragment dialog = new ChooseLocationDialogFragment();
                Bundle args = new Bundle();
                args.putString(ChooseLocationDialogFragment.ARG_INITIAL_LOCATION, torrentInfo.getDownloadDir());
                dialog.setArguments(args);
                dialog.show(getSupportFragmentManager(), TAG_CHOOSE_LOCATION_DIALOG);
                return true;
            case R.id.action_verify:
                getTransportManager().doRequest(new VerifyTorrentRequest(torrent.getId()), null);
                return true;
            case R.id.action_reannounce:
                getTransportManager().doRequest(new ReannounceTorrentRequest(torrent.getId()), null);
                return true;
            case R.id.action_add_all_trackers:
                getTrackers("https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_all.txt");
                return true;
            case R.id.action_add_best_trackers:
                getTrackers("https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt");
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void getTrackers(String url) {
        Request request = new Request.Builder()
                .get()
                .url(url)
                .build();
        getOkHttpClient().newCall(request)
                .enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        toast(e.getMessage());
                    }

                    @Override
                    public void onResponse(Call call, Response response) throws IOException {
                        try {
                            addTracker(response.body().string());
                        } catch (IOException e) {
                            e.printStackTrace();
                            toast(e.getMessage());
                        }
                    }
                });
    }

    private OkHttpClient getOkHttpClient() {
        if (okHttpClient == null) {
            okHttpClient = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(20, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .build();
        }
        return okHttpClient;
    }

    private void toast(CharSequence charSequence) {
        Toast.makeText(TorrentDetailsActivity.this, charSequence, Toast.LENGTH_SHORT).show();
    }

    private void addTracker(String url) {
        getTransportManager().doRequest(new TrackerAddRequest(torrent.getId(), url), new RequestListener<Void>() {
            @Override
            public void onRequestSuccess(Void aVoid) {
                toast("添加成功");
            }

            @Override
            public void onRequestFailure(SpiceException spiceException) {
                toast(spiceException.getMessage());
            }
        });
    }

    @Override
    public void onSavePressed() {
        for (int i=0; i<saveChangesRequests.size(); i++) {
            TorrentSetRequest request = saveChangesRequests.valueAt(i);
            getTransportManager().doRequest(request, null);
        }
        super.onBackPressed();
    }

    @Override
    public void onDiscardPressed() {
        super.onBackPressed();
    }

    @Override
    public void onRemoveTorrentsSelected(int[] torrentsToRemove, boolean removeData) {
        getTransportManager().doRequest(new TorrentRemoveRequest(torrentsToRemove, removeData), new RequestListener<Void>() {
            @Override
            public void onRequestFailure(SpiceException spiceException) {
                Toast.makeText(TorrentDetailsActivity.this, R.string.remove_failed, Toast.LENGTH_SHORT).show();
                Log.e(TAG, "Failed to remove torrent", spiceException);
            }

            @Override
            public void onRequestSuccess(Void aVoid) {
                TorrentDetailsActivity.super.onBackPressed();
            }
        });
    }

    @Override
    public void onLocationSelected(String path, boolean moveData) {
        getTransportManager().doRequest(new SetLocationRequest(path, moveData, torrent.getId()), new RequestListener<Void>() {
            @Override
            public void onRequestSuccess(Void aVoid) {
                torrentInfoUpdater.updateNow(TorrentDetailsActivity.this);
            }

            @Override
            public void onRequestFailure(SpiceException spiceException) {
                Log.e(TAG, "Failed to set location", spiceException);
            }
        });
    }

    @Override
    public void onNameSelected(final int torrentId, String path, String name) {
        getTransportManager().doRequest(new RenameRequest(torrentId, path, name), new RequestListener<Void>() {
            @Override
            public void onRequestSuccess(Void aVoid) {
                updateTorrentAndTorrentInfo();
            }

            @Override
            public void onRequestFailure(SpiceException spiceException) {
                Log.e(TAG, "Failed to rename torrent", spiceException);
            }

            private void updateTorrentAndTorrentInfo() {
                getTransportManager().doRequest(new TorrentGetRequest(torrentId), new RequestListener<Torrents>() {
                    @Override
                    public void onRequestSuccess(Torrents torrents) {
                        if (torrents.size() != 1) {
                            Log.e(TAG, "Wrong number of torrents");
                            return;
                        }

                        updateTorrentInfo(torrents.get(0));
                    }

                    @Override
                    public void onRequestFailure(SpiceException spiceException) {
                        Log.e(TAG, "Failed to reload torrent", spiceException);
                    }
                });
            }

            private void updateTorrentInfo(final Torrent torrent) {
                getTransportManager().doRequest(new TorrentInfoGetRequest(torrent.getId()), new RequestListener<TorrentInfo>() {
                    @Override
                    public void onRequestSuccess(TorrentInfo torrentInfo) {
                        onTorrentInfoUpdated(torrentInfo);
                        TorrentDetailsActivity.this.torrent = torrent;
                        getIntent().putExtra(EXTRA_TORRENT, torrent);
                        setupPager();
                    }

                    @Override
                    public void onRequestFailure(SpiceException spiceException) {
                        Log.e(TAG, "Failed to reload torrent", spiceException);
                    }
                });
            }
        });
    }

    @Override
    public void onRefresh() {
        torrentInfoUpdater.updateNow(this);
    }
}
