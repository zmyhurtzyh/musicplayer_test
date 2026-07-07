package com.musicplayer.app.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.musicplayer.app.R;
import com.musicplayer.app.databinding.ActivityMainBinding;
import com.musicplayer.app.player.MusicServiceConnector;
import com.musicplayer.app.player.PlaybackState;
import com.musicplayer.app.data.Song;
import com.musicplayer.app.ui.home.HomeFragment;
import com.musicplayer.app.ui.library.LibraryFragment;
import com.musicplayer.app.ui.login.LoginActivity;
import com.musicplayer.app.ui.playing.PlayingActivity;
import com.musicplayer.app.ui.search.SearchFragment;
import com.musicplayer.app.util.PermissionHelper;
import com.musicplayer.app.util.UserManager;

import java.util.Arrays;
import java.util.List;

/**
 * 应用主界面容器，采用 ViewPager2 + BottomNavigationView 的经典多 Tab 架构。
 * 承载首页（HomeFragment）、我的音乐（LibraryFragment）和搜索（SearchFragment）三个页面，
 * 禁止左右手势滑动切换，仅通过底部导航栏切换。底部固定 MiniPlayer 栏，
 * 有播放队列时自动显示，点击可跳转全屏播放页。
 * 同时负责音频权限的申请与结果分发，以及向子 Fragment 暴露 MusicServiceConnector。
 */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    /** 音乐服务连接器，与 MusicService 通信 */
    private final MusicServiceConnector connector = new MusicServiceConnector();

    /** 创建跳转 MainActivity 的 Intent */
    public static Intent newIntent(Context context) {
        return new Intent(context, MainActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 未登录则跳转登录页，登录页关闭后不会再回到主界面
        if (!UserManager.get(this).isLoggedIn()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setupViewPager();
        setupBottomNav();
        setupSearchBox();
        setupMiniPlayer();
    }

    /**
     * 初始化 ViewPager2，装载三个 Fragment 页面。
     * 禁止用户手势滑动（setUserInputEnabled(false)），
     * 仅允许通过底部导航栏切换，防止误触。
     * 设置 offscreenPageLimit=2 保持所有页面常驻内存，避免切换时重建。
     */
    private void setupViewPager() {
        List<Fragment> fragments = Arrays.asList(
                new HomeFragment(),
                new LibraryFragment(),
                new SearchFragment());
        binding.viewPager.setAdapter(new PagerAdapter(this, fragments));
        binding.viewPager.setUserInputEnabled(false);
        binding.viewPager.setOffscreenPageLimit(2);
    }

    /**
     * 初始化底部导航栏，选中不同 Tab 时切换 ViewPager 页面。
     * 首页和我的页面显示搜索框，搜索页面隐藏搜索框（避免重复）。
     */
    private void setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_home) {
                binding.viewPager.setCurrentItem(0, false);
                binding.searchBox.setVisibility(View.VISIBLE);
                return true;
            } else if (id == R.id.nav_library) {
                binding.viewPager.setCurrentItem(1, false);
                binding.searchBox.setVisibility(View.VISIBLE);
                return true;
            } else if (id == R.id.nav_search) {
                binding.viewPager.setCurrentItem(2, false);
                // 搜索页自带搜索输入框，隐藏顶部的搜索入口
                binding.searchBox.setVisibility(View.GONE);
                return true;
            }
            return false;
        });
    }

    /** 点击顶部搜索框跳转到搜索页（第三个 Tab） */
    private void setupSearchBox() {
        binding.searchBox.setOnClickListener(v -> {
            binding.viewPager.setCurrentItem(2, true);
            binding.searchBox.setVisibility(View.GONE);
        });
    }

    /**
     * 初始化 MiniPlayer 栏，绑定 MusicServiceConnector 并订阅播放状态。
     * 有播放队列时显示 MiniPlayer，点击跳转全屏播放页。
     */
    private void setupMiniPlayer() {
        binding.miniPlayer.bind(connector);
        binding.miniPlayer.setOnClickListener(v -> {
            startActivity(new Intent(this, PlayingActivity.class));
        });
        // 有播放队列时显示 MiniPlayer，无队列时隐藏
        PlaybackState.get().currentIndex.observe(this, idx ->
                binding.miniPlayer.setVisibility(
                        idx != null && idx >= 0 ? View.VISIBLE : View.GONE));
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 绑定音乐服务，绑定成功后刷新 MiniPlayer
        connector.bind(this, () -> binding.miniPlayer.refresh());

        // 申请音频权限（Android 13+ 的 READ_MEDIA_AUDIO）
        if (!PermissionHelper.hasAudioPermission(this)) {
            PermissionHelper.requestAudioPermission(this);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        connector.unbind(this);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PermissionHelper.REQ_AUDIO) {
            // 检查是否所有权限都被授予
            boolean granted = grantResults.length > 0;
            for (int g : grantResults) {
                if (g != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                    break;
                }
            }
            if (!granted) {
                showPermissionDialog();
            } else {
                // 权限授予后通知所有实现了 PermissionAware 的 Fragment 刷新数据
                notifyPermissionGranted();
            }
        }
    }

    /**
     * 权限被拒后弹出对话框，引导用户去系统设置开启权限或重试申请。
     */
    private void showPermissionDialog() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.perm_denied)
                .setPositiveButton(R.string.perm_setting, (d, w) ->
                        PermissionHelper.openAppSettings(this))
                .setNegativeButton(R.string.perm_retry, (d, w) ->
                        PermissionHelper.requestAudioPermission(this))
                .setCancelable(false)
                .show();
    }

    /**
     * 权限授予后遍历所有子 Fragment，通知实现了 PermissionAware 接口的 Fragment 重新加载数据。
     */
    private void notifyPermissionGranted() {
        for (Fragment f : getSupportFragmentManager().getFragments()) {
            if (f instanceof PermissionAware) {
                ((PermissionAware) f).onPermissionGranted();
            }
        }
    }

    /**
     * 暴露 ServiceConnector 给子 Fragment 使用（如播放歌曲）。
     * @return 当前绑定的 MusicServiceConnector 实例
     */
    public MusicServiceConnector getConnector() {
        return connector;
    }

    /**
     * 从首页发起播放，将歌曲列表和目标歌曲传给 MusicService。
     * @param songs  完整的歌曲播放列表
     * @param target 要立即播放的目标歌曲
     */
    public void playFromHome(List<Song> songs, Song target) {
        connector.playSongs(songs, target);
    }

    /**
     * 刷新首页数据，查找 HomeFragment 并调用其 onPermissionGranted 方法重新加载。
     */
    public void refreshHome() {
        for (Fragment f : getSupportFragmentManager().getFragments()) {
            if (f instanceof HomeFragment) {
                ((HomeFragment) f).onPermissionGranted();
                break;
            }
        }
    }

    /**
     * 权限感知接口，Fragment 实现该接口以接收权限授予事件。
     * 当音频权限被授予后，MainActivity 会遍历所有子 Fragment 并调用此方法，
     * Fragment 可在回调中重新加载依赖权限的数据（如本地歌曲列表）。
     */
    public interface PermissionAware {
        /** 音频权限已授予，可以安全地访问本地音乐数据 */
        void onPermissionGranted();
    }

    /**
     * ViewPager2 的 FragmentStateAdapter，持有预创建的 Fragment 列表。
     * 由于 ViewPager2 设置了 offscreenPageLimit=2，所有 Fragment 常驻内存，
     * createFragment 直接返回列表中的实例。
     */
    private static class PagerAdapter extends FragmentStateAdapter {
        private final List<Fragment> fragments;

        PagerAdapter(@NonNull FragmentActivity fa, List<Fragment> fragments) {
            super(fa);
            this.fragments = fragments;
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            return fragments.get(position);
        }

        @Override
        public int getItemCount() {
            return fragments.size();
        }
    }
}
