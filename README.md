# MusicPlayer - Android 本地音乐播放器

> 一个Android 本地音乐播放器，其参考网易云音乐 UI 风格，基于 Java 语言开发的 Android 本地音乐播放器，支持多用户注册登录与数据隔离。

---

## 开发者

**曾铭杨** — 成都理工大学 · 智能科学与技术（202319120125）

## 项目简介

本项目由成都理工大学的智能科学与技术专业的曾铭杨制作完成，本项目是大三学期安卓开发课程的期末项目。

代码编写和逻辑功能处理与界面设计可能还有问题和可以优化的地方，欢迎大家在该仓库提出建议和指正！！！

## 目录

- [功能特性](#功能特性)
- [整体架构](#整体架构)
- [核心模块详解](#核心模块详解)
  - [播放服务](#1-播放服务musicservice)
  - [封面加载](#2-封面加载coverloader--embeddedartreader)
  - [歌词系统](#3-歌词系统lyricparser--playingactivity)
  - [多用户与数据隔离](#4-多用户与数据隔离usermanager--userdao)
  - [歌单管理](#5-歌单管理playlistdao--databasehelper)
  - [首页推荐](#6-首页推荐homefragment)
- [数据库设计](#数据库设计)
- [项目结构](#项目结构)
- [构建与运行](#构建与运行)
- [歌词使用](#歌词使用)

---

## 功能特性

| 模块 | 功能 |
|------|------|
| 播放 | 后台播放、通知栏控制、锁屏控件、MediaSession、播放队列管理 |
| 控制 | 播放/暂停/上一首/下一首、进度条拖动、三种播放模式切换 |
| 歌词 | 三级回退歌词加载、行高亮滚动、点击歌词跳转、拖动进度条实时同步 |
| 用户 | 多用户注册登录、数据隔离、SP+DB 双重校验、退出登录 |
| 歌单 | 内置歌单自动创建、自建歌单增删、收藏/取消收藏、播放次数按用户隔离 |
| 搜索 | 实时过滤本地歌曲（标题/歌手/专辑，不区分大小写） |
| 首页 | 最近播放、每日推荐（未听优先）、排行榜（按用户播放次数排序） |
| UI | 深蓝紫渐变播放页、黑胶唱片无缝旋转+唱针动画、BottomSheet 弹窗 |

---

## 整体架构

```
┌─────────────────────────────────────────────────────┐
│                      UI 层                          │
│  MainActivity / HomeFragment / LibraryFragment /    │
│  SearchFragment / PlayingActivity / DialogFragments │
│         │              │              │              │
│     ViewBinding    LiveData 观察   ServiceConnector  │
└─────────┼──────────────┼──────────────┼─────────────┘
          │              │              │
┌─────────▼──────────────▼──────────────▼─────────────┐
│                   状态层                            │
│              PlaybackState (LiveData 单例)           │
│   isPlaying / currentIndex / position / playMode    │
└─────────────────────────┬───────────────────────────┘
                          │ postValue
┌─────────────────────────▼───────────────────────────┐
│                   服务层                            │
│              MusicService (前台 Service)              │
│   MediaPlayer / 通知栏 / MediaSession / 队列管理     │
└─────────┬───────────────────────────────────┬───────┘
          │ 读写                                │ 读写
┌─────────▼───────────┐         ┌─────────────▼───────┐
│     数据层          │         │     系统接口         │
│  DatabaseHelper     │         │  MediaStore          │
│  PlaylistDao        │         │  MediaMetadataRetriever│
│  UserDao            │         │  MediaScannerConnection│
│  MusicRepository    │         └─────────────────────┘
└─────────────────────┘
```

**数据流向**：MusicService 通过 PlaybackState（LiveData）推送状态变化 → UI 层 observe 自动刷新。UI 层通过 MusicServiceConnector 调用 Service 方法控制播放。数据层独立提供持久化能力。

---

## 核心模块详解

### 1. 播放服务（MusicService）

MusicService 是应用的核心，继承 `MediaBrowserServiceCompat`，承担以下职责：

- **音频播放**：通过 `MediaPlayer` 完成音频解码与输出，使用 `prepareAsync()` 异步准备避免阻塞主线程
- **队列管理**：维护播放队列 `List<Song>`，支持设置队列、播放指定歌曲、移除队列项、切歌等操作
- **播放模式**：`LIST`（列表循环）→ `REPEAT_ONE`（单曲循环）→ `SHUFFLE`（随机播放），通过 `PlayMode` 枚举管理
- **前台通知**：通过 `startForeground()` 显示常驻通知，保证后台播放不被系统回收；Android 14+ 指定 `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`
- **MediaSession**：与系统媒体控制框架（通知栏、蓝牙、Android Auto）集成，支持外部控制播放
- **进度刷新**：每 500ms 通过 `Handler.postDelayed` 读取播放位置，通过 `PlaybackState.position.postValue()` 推送给 UI
- **历史记录**：播放歌曲时自动写入"最近播放"歌单，并累加播放次数

```
播放流程：
setQueue() → prepareAndPlay() → MediaPlayer.prepareAsync()
    → onPrepared 回调 → player.start() + 更新 PlaybackState + 写入播放历史
    → progressTick 每500ms 刷新进度
    → onCompletion 回调 → 根据播放模式决定 next() 或重新播放
```

### 2. 封面加载（CoverLoader + EmbeddedArtReader）

采用**两级回退 + 内存缓存 + 异步加载**策略：

```
display(song, target)
  │
  ├─ LruCache 命中？ → 直接显示
  │
  └─ 未命中 → 设占位图 → 启动 DecodeTask (AsyncTask)
                    │
                    ▼ doInBackground
              EmbeddedArtReader.read()
                    │
                    ├─ 第1级：MediaMetadataRetriever.getEmbeddedPicture()
                    │         读取音频文件内嵌封面（MP3 的 APIC 帧 / FLAC 的 PICTURE 块）
                    │
                    └─ 第2级：content://media/external/audio/albumart/{albumId}
                              从 MediaStore 获取专辑封面
                    │
                    ▼ onPostExecute
              写入 LruCache → 通过 tag 校验防复用错位 → 显示到 ImageView
```

- **缓存**：LruCache 以 `mediaStoreId` 为 key，大小为可用内存的 1/8（上限 32MB）
- **防错位**：ImageView 设置 `tag(R.id.cover_tag_song_id, mediaStoreId)`，回调时校验 tag 确认未被复用

### 3. 歌词系统（LyricParser + PlayingActivity）

**歌词加载**：采用三级回退策略，在子线程中依次尝试

| 优先级 | 来源 | 方式 |
|--------|------|------|
| 1 | 同目录 .lrc 文件 | 将音频文件扩展名替换为 `.lrc` / `.LRC` 读取 |
| 2 | assets 目录 | `assets/lyrics/{歌曲标题}.lrc` |
| 3 | 音频内嵌歌词 | `MediaMetadataRetriever` 的 metadata key 100（同步歌词）/ 101（非同步歌词） |

**歌词解析**：`LyricParser` 解析 `[mm:ss.xx]` 时间标签，生成按时间排序的 `Lyric` 列表

**歌词同步**：每次播放位置更新时，通过 `LyricParser.findCurrentLine()` 二分查找当前应高亮的歌词行

### 4. 多用户与数据隔离（UserManager + UserDao）

```
注册流程：
LoginActivity → UserManager.register() → UserDao.register()
  → INSERT INTO users (CONFLICT_IGNORE, username 唯一约束)

登录流程：
LoginActivity → UserManager.login() → UserDao.login()
  → SELECT id FROM users WHERE username=? AND password=?
  → 成功后：写入 SharedPreferences + 调用 ensureBuiltinPlaylists()

登录校验：
UserManager.isLoggedIn()
  → 检查 SP 中的 logged_in 标志
  → 检查 SP 中的 userId 是否有效
  → 查询数据库确认该用户是否仍存在（防止旧缓存导致假登录）
```

**数据隔离**：所有用户数据通过 `user_id` 字段隔离
- 歌单：`playlists.user_id` 标识所属用户
- 播放次数：`playlist_song.play_count` 按用户+歌单维度统计
- 收藏：收藏歌单属于特定用户，查询时带 `user_id` 条件

### 5. 歌单管理（PlaylistDao + DatabaseHelper）

**歌单类型**：
- `TYPE_USER = 0`：用户自建歌单，可创建和删除
- `TYPE_FAVORITE = 1`："我喜欢的音乐"，内置不可删除
- `TYPE_HISTORY = 2`："最近播放"，内置不可删除

**防重复机制**：`ensureBuiltinPlaylists()` 在插入前先查询 `type + user_id` 是否已存在，避免重复创建

**最近播放写入**：每次播放歌曲时，先从"最近播放"歌单中移除该歌曲（保留旧播放次数），再重新添加到顶部，实现"最近播放的歌曲排在最前"

**播放次数**：存储在 `playlist_song.play_count`，通过 `incrementPlayCount()` 原子自增 `+1`

**失效清理**：查询歌单歌曲时通过 MediaStore 校验文件是否仍存在，自动清理已删除文件对应的记录

### 6. 首页推荐（HomeFragment）

首页展示三个分区，数据来源和排序逻辑：

| 分区 | 数据来源 | 排序方式 |
|------|----------|----------|
| 最近播放 | 最近播放歌单前 10 首 | 按添加时间倒序（最新播放排最前） |
| 每日推荐 | 优先未播放过的歌曲，不足时从全部歌曲随机补充 | 随机排列 |
| 排行榜 | 按播放次数倒序，不足 6 首时按时长倒序补足 | 播放次数降序 |

---

## 数据库设计

### 表结构

**songs 表**（歌曲元数据，全局共享）

| 列名 | 类型 | 说明 |
|------|------|------|
| id | INTEGER PK | 自增主键 |
| media_store_id | INTEGER UNIQUE | MediaStore 中的 _ID，唯一标识一首歌 |
| title | TEXT | 歌曲标题 |
| artist | TEXT | 歌手 |
| album | TEXT | 专辑 |
| duration | INTEGER | 时长（毫秒） |
| play_count | INTEGER | 旧版全局播放次数（v5 后已迁移，仅做兼容） |

**playlists 表**（歌单）

| 列名 | 类型 | 说明 |
|------|------|------|
| id | INTEGER PK | 自增主键 |
| name | TEXT | 歌单名称 |
| type | INTEGER | 0=用户自建，1=我喜欢的音乐，2=最近播放 |
| created_at | INTEGER | 创建时间（毫秒时间戳） |
| user_id | INTEGER | 所属用户 ID |

**playlist_song 表**（歌单-歌曲关联）

| 列名 | 类型 | 说明 |
|------|------|------|
| playlist_id | INTEGER | 关联的歌单 ID（联合主键） |
| song_id | INTEGER | 关联的 songs 表主键（联合主键） |
| added_at | INTEGER | 添加时间（毫秒时间戳） |
| play_count | INTEGER | 该歌曲在该歌单中的播放次数（按用户隔离） |

**users 表**（用户信息）

| 列名 | 类型 | 说明 |
|------|------|------|
| id | INTEGER PK | 自增主键 |
| username | TEXT UNIQUE | 用户名（唯一） |
| password | TEXT | 密码（明文，本地应用简化处理） |
| nickname | TEXT | 昵称 |
| created_at | INTEGER | 注册时间（毫秒时间戳） |

### 版本历史

| 版本 | 变更 |
|------|------|
| v1 | 初始建表：songs / playlists / playlist_song |
| v2 | songs 表新增 play_count 列 |
| v3 | 新增 users 表，支持多用户 |
| v4 | playlists 表新增 user_id 列，歌单按用户隔离 |
| v5 | playlist_song 表新增 play_count 列，播放次数按用户隔离 |
| v6 | 清理旧版产生的重复内置歌单，每个用户每种类型仅保留最早一条 |

### 关键设计说明

- **播放次数隔离**：同一首歌在不同用户的"最近播放"歌单中有独立的 `play_count`，互不干扰
- **联合主键防重复**：`playlist_song` 表的 `(playlist_id, song_id)` 联合主键防止同一首歌被重复添加到同一歌单
- **外键级联删除**：删除歌单时自动清理 `playlist_song` 中的关联记录
- **内置歌单保护**：`deletePlaylist()` 方法检查歌单类型，内置歌单（type > 0）返回 false 拒绝删除

---

## 项目结构

```
app/src/main/java/com/musicplayer/app/
├── MusicApp.java                         # Application 入口，初始化 CoverLoader 和 UserManager
│
├── data/                                 # 数据层
│   ├── Song.java                         # 歌曲模型（Parcelable，以 mediaStoreId 为唯一标识）
│   ├── Playlist.java                     # 歌单模型（Parcelable，含类型 TYPE_USER/FAVORITE/HISTORY）
│   ├── DatabaseHelper.java               # SQLite 助手（建表 + v1→v6 版本升级）
│   ├── PlaylistDao.java                  # 歌单 DAO（增删查改、播放次数、排行榜、失效清理）
│   ├── UserDao.java                      # 用户 DAO（注册/登录/查询，CONFLICT_IGNORE 防重复注册）
│   ├── MusicRepository.java              # 本地音乐扫描（MediaStore + 文件校验 + 媒体扫描同步）
│   ├── Lyric.java                        # 歌词行模型（时间戳 + 文本）
│   └── LyricParser.java                  # LRC 解析器（时间标签解析 + 二分查找定位当前行）
│
├── player/                               # 播放层
│   ├── MusicService.java                 # 前台播放服务（MediaPlayer + 队列 + 通知 + MediaSession）
│   ├── PlaybackState.java                # 全局 LiveData 状态中心（DCL 单例，Service→UI 通信桥梁）
│   ├── PlayMode.java                     # 播放模式枚举（LIST → REPEAT_ONE → SHUFFLE 循环切换）
│   └── MusicServiceConnector.java        # Service 绑定桥接器（bind/unbind + 便捷控制方法）
│
├── ui/                                   # 界面层
│   ├── MainActivity.java                 # 主容器（ViewPager2 + BottomNav + MiniPlayer + 权限管理）
│   ├── home/HomeFragment.java            # 首页（最近播放 / 每日推荐 / 排行榜三个分区）
│   ├── library/LibraryFragment.java      # 我的（本地音乐入口 + 歌单列表 + 退出登录）
│   ├── login/LoginActivity.java          # 登录/注册（模式切换 + 输入校验）
│   ├── search/SearchFragment.java        # 搜索（实时过滤 + 收藏操作 + 懒加载）
│   ├── playing/PlayingActivity.java      # 全屏播放（黑胶唱片 + 歌词 + 播放控制 + 收藏/加入歌单）
│   ├── playing/PlayQueueDialogFragment.java   # 播放队列弹窗（实时监听队列变化）
│   ├── library/PlaylistDetailDialogFragment.java  # 歌单详情弹窗（全部播放 + 收藏 + 更多操作）
│   ├── song/SongMoreDialogFragment.java        # 歌曲更多操作（收藏/加入歌单/歌曲信息）
│   ├── mini/MiniPlayerView.java          # 底部悬浮播放栏（封面 + 进度条 + 播放控制）
│   └── adapter/
│       ├── SongAdapter.java              # 歌曲列表适配器（可选序号 + 收藏按钮 + 播放高亮）
│       ├── PlaylistAdapter.java          # 歌单列表适配器（本地音乐入口 + 歌单项）
│       └── HomeSectionAdapter.java       # 首页分区卡片适配器（标题 + 横向滚动卡片）
│
└── util/                                 # 工具层
    ├── UserManager.java                  # 用户状态管理（登录/注册/退出，SP + DB 双重校验）
    ├── CoverLoader.java                  # 封面加载器（LruCache + AsyncTask + tag 防错位）
    ├── EmbeddedArtReader.java            # 嵌入式封面读取（内嵌封面优先 → 专辑封面回退）
    ├── PermissionHelper.java             # 权限适配（Android 13+ READ_MEDIA_AUDIO / POST_NOTIFICATIONS）
    └── FormatUtil.java                   # 格式化工具（时长 mm:ss / 文件大小 KB/MB/GB）
```

---

## 构建与运行

### 前置条件
- Android Studio Flamingo (2022.2.1) 或更高版本
- Android SDK (compileSdk 34)
- JDK 17

### 步骤
1. 用 Android Studio 打开本项目根目录
2. 等待 Gradle Sync 完成
3. 连接 Android 设备或启动模拟器
4. 点击 **Run** 运行

> 如果 `local.properties` 中的 SDK 路径与你的不一致，请修改该文件。

### 权限说明
- **Android 13+**：需要 `READ_MEDIA_AUDIO` 权限读取本地音乐
- **Android 12 及以下**：需要 `READ_EXTERNAL_STORAGE` 权限
- **Android 13+**：需要 `POST_NOTIFICATIONS` 显示播放通知

首次启动会自动请求权限，拒绝后可在设置中手动开启。

---

## 歌词使用
1. 将 `.lrc` 歌词文件放在歌曲同目录下
2. 歌词文件名需与歌曲文件名一致（仅后缀不同）
   - 例如：`夜曲.mp3` → `夜曲.lrc`
3. 播放器支持 UTF-8 和 GBK 编码自动检测
4. 也支持从 `assets/lyrics/` 目录或音频内嵌歌词加载

`assets/demo.lrc` 中有一个示例歌词文件可供参考。

---

## 许可证
本项目仅供学习交流使用。

---

