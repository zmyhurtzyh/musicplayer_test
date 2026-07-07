package com.musicplayer.app.ui.login;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.musicplayer.app.R;
import com.musicplayer.app.ui.MainActivity;
import com.musicplayer.app.util.UserManager;

/**
 * 登录/注册页面。
 * <p>
 * 支持多用户注册和登录，用户数据存储在 SQLite users 表中。
 * 登录成功后跳转到 MainActivity，注册成功后自动切回登录模式。
 * </p>
 */
public class LoginActivity extends AppCompatActivity {

    /** true=注册模式，false=登录模式 */
    private boolean isRegisterMode = false;

    private EditText etUsername;
    private EditText etPassword;
    private EditText etNickname;
    private TextView tvSwitch;
    private TextView btnLogin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 已登录则直接跳转
        UserManager um = UserManager.get(this);
        if (um.isLoggedIn()) {
            startActivity(MainActivity.newIntent(this));
            finish();
            return;
        }

        setContentView(R.layout.activity_login);

        etUsername = findViewById(R.id.login_username);
        etPassword = findViewById(R.id.login_password);
        etNickname = findViewById(R.id.login_nickname);
        tvSwitch = findViewById(R.id.login_switch);
        btnLogin = findViewById(R.id.login_btn);

        // 切换登录/注册模式
        tvSwitch.setOnClickListener(v -> {
            isRegisterMode = !isRegisterMode;
            etNickname.setVisibility(isRegisterMode ? View.VISIBLE : View.GONE);
            tvSwitch.setText(isRegisterMode
                    ? R.string.switch_to_login
                    : R.string.switch_to_register);
            btnLogin.setText(isRegisterMode
                    ? R.string.register
                    : R.string.login);
        });

        // 登录/注册按钮
        findViewById(R.id.login_btn).setOnClickListener(v -> {
            String username = etUsername.getText().toString().trim();
            String password = etPassword.getText().toString().trim();

            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, R.string.input_empty, Toast.LENGTH_SHORT).show();
                return;
            }

            UserManager mgr = UserManager.get(this);

            if (isRegisterMode) {
                String nickname = etNickname.getText().toString().trim();
                if (nickname.isEmpty()) nickname = username;

                if (mgr.exists(username)) {
                    Toast.makeText(this, R.string.user_exists, Toast.LENGTH_SHORT).show();
                    return;
                }

                long id = mgr.register(username, password, nickname);
                if (id > 0) {
                    Toast.makeText(this, R.string.register_success, Toast.LENGTH_SHORT).show();
                    // 注册成功后切回登录模式
                    isRegisterMode = false;
                    etNickname.setVisibility(View.GONE);
                    tvSwitch.setText(R.string.switch_to_register);
                    btnLogin.setText(R.string.login);
                } else {
                    Toast.makeText(this, R.string.register_fail, Toast.LENGTH_SHORT).show();
                }
            } else {
                if (mgr.login(username, password)) {
                    startActivity(MainActivity.newIntent(this));
                    finish();
                } else {
                    Toast.makeText(this, R.string.login_fail, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }
}
