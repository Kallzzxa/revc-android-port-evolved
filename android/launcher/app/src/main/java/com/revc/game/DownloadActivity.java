package com.revc.game;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.revc.game.databinding.ActivityDownloadBinding;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Setup screen shown when the game data is missing.
 *
 * Downloads the GTA Vice City PC data pack (1.19 GB, resumable), extracts
 * it into the game folder and verifies it, then hands over to GameActivity.
 * GameActivity redirects here when the data is missing, so the game engine
 * never starts without files to load.
 */
public class DownloadActivity extends AppCompatActivity {

    private ActivityDownloadBinding binding;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityDownloadBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        File gameDir = GameActivity.getGameDir();
        if (!gameDir.exists()) {
            gameDir.mkdirs();
        }

        // Opened directly (or data appeared meanwhile): go straight to the game.
        if (GameDataDownloader.isGameDataPresent(gameDir)) {
            startGame();
            return;
        }

        showIdleState();

        binding.btnDownload.setOnClickListener(v -> {
            if (busy) {
                return;
            }
            startDownloadAndExtract();
        });
        binding.btnCancel.setOnClickListener(v -> {
            cancelled.set(true);
            GameDataDownloader.cancelCurrentDownload();
        });
        binding.btnPlay.setOnClickListener(v -> startGame());
    }

    @Override
    protected void onDestroy() {
        cancelled.set(true);
        GameDataDownloader.cancelCurrentDownload();
        executor.shutdownNow();
        super.onDestroy();
    }

    /** Idle state: button text follows what is already on disk. */
    private void showIdleState() {
        File gameDir = GameActivity.getGameDir();
        File rar = GameDataDownloader.getDownloadFile(gameDir);
        long have = rar.exists() ? rar.length() : 0;
        if (have >= GameDataDownloader.EXPECTED_SIZE) {
            binding.statusText.setText("File sudah terdownload, tinggal diekstrak.");
            binding.btnDownload.setText(R.string.download_extract_button);
        } else if (have > 0) {
            binding.statusText.setText("Download sebelumnya: "
                    + GameDataDownloader.formatMB(have) + " dari "
                    + GameDataDownloader.formatMB(GameDataDownloader.EXPECTED_SIZE) + ".");
            binding.btnDownload.setText(R.string.download_resume_button);
        } else {
            binding.statusText.setText(getString(R.string.download_intro));
            binding.btnDownload.setText(R.string.download_button);
        }
        binding.detailText.setText("");
        binding.progressBar.setProgress(0);
        binding.btnCancel.setVisibility(View.GONE);
        binding.btnPlay.setVisibility(View.GONE);
        binding.btnDownload.setVisibility(View.VISIBLE);
    }

    private void startDownloadAndExtract() {
        File gameDir = GameActivity.getGameDir();
        if (gameDir.getUsableSpace() < GameDataDownloader.REQUIRED_FREE_BYTES) {
            binding.statusText.setText(getString(R.string.download_no_space,
                    GameDataDownloader.formatMB(gameDir.getUsableSpace())));
            return;
        }
        busy = true;
        cancelled.set(false);
        binding.btnDownload.setVisibility(View.GONE);
        binding.btnPlay.setVisibility(View.GONE);
        binding.btnCancel.setVisibility(View.VISIBLE);

        executor.execute(() -> {
            try {
                GameDataDownloader.download(gameDir, cancelled, (downloaded, total, bytesPerSec) ->
                        runOnUiThread(() -> {
                            binding.statusText.setText(R.string.download_downloading);
                            binding.detailText.setText(GameDataDownloader.formatMB(downloaded)
                                    + " / " + GameDataDownloader.formatMB(total)
                                    + "  •  " + (int) (100.0 * downloaded / total) + "%  •  "
                                    + GameDataDownloader.formatMB(bytesPerSec) + "/dtk");
                            binding.progressBar.setProgress(
                                    (int) (1000.0 * downloaded / total));
                        }));
                if (cancelled.get()) {
                    runOnUiThread(this::onCancelled);
                    return;
                }

                GameDataDownloader.extract(gameDir, cancelled, (done, total, name) ->
                        runOnUiThread(() -> {
                            binding.statusText.setText(R.string.download_extracting);
                            binding.detailText.setText(done + " / " + total + " file  •  " + name);
                            binding.progressBar.setProgress(
                                    total > 0 ? (int) (1000.0 * done / total) : 0);
                        }));
                if (cancelled.get()) {
                    runOnUiThread(this::onCancelled);
                    return;
                }

                if (!GameDataDownloader.isGameDataPresent(gameDir)) {
                    throw new IllegalStateException(getString(R.string.download_verify_failed));
                }
                GameDataDownloader.deleteDownload(gameDir);
                runOnUiThread(() -> {
                    busy = false;
                    binding.statusText.setText(R.string.download_done);
                    binding.detailText.setText("Data game sudah siap dimainkan.");
                    binding.progressBar.setProgress(1000);
                    binding.btnCancel.setVisibility(View.GONE);
                    binding.btnPlay.setVisibility(View.VISIBLE);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    busy = false;
                    if (cancelled.get()) {
                        onCancelled();
                        return;
                    }
                    binding.btnCancel.setVisibility(View.GONE);
                    binding.btnDownload.setVisibility(View.VISIBLE);
                    showIdleState();
                    // Keep the error visible above the idle text.
                    binding.statusText.setText(getString(R.string.download_error,
                            e.getMessage() != null ? e.getMessage() : e.toString()));
                });
            }
        });
    }

    private void onCancelled() {
        busy = false;
        binding.detailText.setText("");
        binding.btnCancel.setVisibility(View.GONE);
        binding.btnDownload.setVisibility(View.VISIBLE);
        showIdleState();
        binding.statusText.setText(R.string.download_cancelled);
    }

    private void startGame() {
        startActivity(new Intent(this, GameActivity.class));
        finish();
    }
}
