package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.ai.subtitle.WhisperModelManager;
import com.fongmi.android.tv.databinding.ActivityAiSubtitleSettingsBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;

import java.io.File;
import java.util.Locale;

public final class AiSubtitleSettingsActivity extends BaseActivity {
    private ActivityAiSubtitleSettingsBinding mBinding;
    private WhisperModelManager modelManager;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, AiSubtitleSettingsActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityAiSubtitleSettingsBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        modelManager = WhisperModelManager.getInstance(this);
        refreshModelState(null);
        if (modelManager.isDownloaded()) mBinding.deleteModelButton.requestFocus();
        else mBinding.modelButton.requestFocus();
    }

    @Override
    protected void initEvent() {
        mBinding.modelButton.setOnClickListener(this::downloadModel);
        mBinding.deleteModelButton.setOnClickListener(this::confirmDeleteModel);
    }

    private void downloadModel(View view) {
        if (modelManager.isDownloading()) return;
        mBinding.modelButton.setEnabled(false);
        mBinding.modelProgress.setVisibility(View.VISIBLE);
        mBinding.modelProgress.setProgress(0);
        mBinding.modelStatus.setText("正在連線下載 Whisper Base 多語模型…");
        modelManager.download(new WhisperModelManager.Listener() {
            @Override
            public void onProgress(long downloadedBytes, long totalBytes) {
                if (isFinishing()) return;
                if (totalBytes > 0) {
                    int percent = (int) Math.min(100L, downloadedBytes * 100L / totalBytes);
                    mBinding.modelProgress.setProgress(percent);
                    mBinding.modelStatus.setText(String.format(Locale.getDefault(),
                            "下載中：%.1f / %.1f MB（%d%%）",
                            downloadedBytes / 1048576.0, totalBytes / 1048576.0, percent));
                } else {
                    mBinding.modelStatus.setText(String.format(Locale.getDefault(),
                            "下載中：%.1f MB", downloadedBytes / 1048576.0));
                }
            }

            @Override
            public void onDownloaded(File file) {
                if (isFinishing()) return;
                refreshModelState("下載完成；啟動字幕時才會載入模型。");
            }

            @Override
            public void onDeleted() {
                refreshModelState("Whisper Base 模型已刪除。");
            }

            @Override
            public void onError(String message) {
                if (isFinishing()) return;
                refreshModelState("模型下載失敗：" + message);
            }
        });
    }

    private void confirmDeleteModel(View view) {
        if (modelManager.isDownloading()) return;
        new AlertDialog.Builder(this)
                .setTitle("刪除 Whisper Base 模型？")
                .setMessage("會刪除約 148 MB 的本機語音辨識模型；日後可重新下載。")
                .setNegativeButton("取消", (dialog, which) -> dialog.dismiss())
                .setPositiveButton("刪除", (dialog, which) ->
                        modelManager.delete(new WhisperModelManager.Listener() {
                            @Override
                            public void onProgress(long downloadedBytes, long totalBytes) { }
                            @Override
                            public void onDownloaded(File file) { }
                            @Override
                            public void onDeleted() {
                                refreshModelState("Whisper Base 模型已刪除。");
                            }
                            @Override
                            public void onError(String message) {
                                refreshModelState("刪除失敗：" + message);
                            }
                        }))
                .show();
    }

    private void refreshModelState(String notice) {
        if (mBinding == null || isFinishing()) return;
        boolean ready = modelManager != null && modelManager.isDownloaded();
        boolean downloading = modelManager != null && modelManager.isDownloading();
        if (ready) {
            mBinding.modelStatus.setText(notice == null
                    ? "模型狀態：Whisper Base 多語版已下載"
                    : notice);
            mBinding.modelButton.setVisibility(View.GONE);
            mBinding.deleteModelButton.setVisibility(View.VISIBLE);
            mBinding.modelProgress.setVisibility(View.GONE);
        } else {
            mBinding.modelStatus.setText(notice == null
                    ? (downloading ? "模型下載中，請稍候…" : "模型狀態：尚未下載")
                    : notice);
            mBinding.modelButton.setVisibility(View.VISIBLE);
            mBinding.modelButton.setEnabled(!downloading);
            mBinding.modelButton.setText(downloading
                    ? "正在下載 Whisper Base…"
                    : "下載 Whisper Base 多語模型（約 148 MB）");
            mBinding.deleteModelButton.setVisibility(View.GONE);
            if (!downloading) mBinding.modelProgress.setVisibility(View.GONE);
        }
    }
}
