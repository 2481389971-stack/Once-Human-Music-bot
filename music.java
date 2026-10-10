import javax.swing.*;
import javax.sound.midi.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.prefs.Preferences;

public class music extends JFrame {
    private JButton selectFileButton, playButton, pauseButton, stopButton, resetButton;
    private JLabel statusLabel, fileLabel, speedLabel, pitchLabel;
    private JSlider progressSlider, speedSlider, pitchSlider;
    private JComboBox<String> modeComboBox;
    private JCheckBox autoAdaptCheckBox; // 乐器模式切换

    private File selectedMidiFile;
    private Sequencer sequencer;
    private Sequence sequence;
    private Robot robot;

    private volatile boolean isPlaying = false;
    private volatile boolean isPaused = false;
    private volatile boolean isUserDraggingSlider = false;

    // 程序内部调整升降调滑块时置 true，用于区分“自动适配”与“用户手动拖动”
    private boolean isAutoAdapting = false;

    /**
     * 相邻两个音符的最小投递间隔（毫秒）。设为 0 即完全不限流（默认）。
     *
     * 依据（本机实测）：钢琴模式下每个音符需要「按修饰 10ms + 主键 8ms + 松修饰 6ms」
     * ≈ 24ms，即程序投递上限约 42 音/秒；本项目 midi/ 目录里最快的谱面峰值 50~56 音/秒
     * （百年孤寂、西班牙斗牛士），平均密度则只有 8~17 音/秒。
     *
     * 模拟显示限流代价极大：一旦启用，同一 tick 的齐奏和弦会被砍成单音，全库平均只剩 63%
     * 的音符（哈尔的移动城堡仅 37%）。因此默认关闭——密集段落只会造成节奏稍拖（良性），
     * 而丢音是恶性退化，不该用后者换前者。仅当你的机器性能不足、游戏明显丢键时才考虑开启。
     */
    private static final int MIN_NOTE_GAP_MS = 0;

    // 仅在 MIN_NOTE_GAP_MS > 0 时用于限流的下述两个字段
    private volatile long lastNoteNanos = 0L;
    private volatile int droppedNotes = 0;

    // 本次播放实际耗时与谱面应有的长度（毫秒），用于判断程序是否跟得上谱面
    private volatile long playbackExpectedMs = 0L;
    private volatile long playbackActualMs = 0L;

    // 调试日志开关：排查错音时置为 true，定位后可改回 false
    private static final boolean DEBUG = false;

    private static final Preferences prefs = Preferences.userNodeForPackage(music.class);
    private static final String LAST_DIRECTORY_KEY = "last_midi_directory";

    // 吉他模式按键映射 (吉他只有 22 个全白键音符)
    private static final Map<Integer, Integer> GUITAR_MAP = new HashMap<>();
    static {
        // 低音区 (C3 ~ B3)
        GUITAR_MAP.put(48, KeyEvent.VK_Z); GUITAR_MAP.put(50, KeyEvent.VK_X); GUITAR_MAP.put(52, KeyEvent.VK_C);
        GUITAR_MAP.put(53, KeyEvent.VK_V); GUITAR_MAP.put(55, KeyEvent.VK_B); GUITAR_MAP.put(57, KeyEvent.VK_N); GUITAR_MAP.put(59, KeyEvent.VK_M);
        // 中音区 (C4 ~ B4)
        GUITAR_MAP.put(60, KeyEvent.VK_A); GUITAR_MAP.put(62, KeyEvent.VK_S); GUITAR_MAP.put(64, KeyEvent.VK_D);
        GUITAR_MAP.put(65, KeyEvent.VK_F); GUITAR_MAP.put(67, KeyEvent.VK_G); GUITAR_MAP.put(69, KeyEvent.VK_H); GUITAR_MAP.put(71, KeyEvent.VK_J);
        // 高音区 (C5 ~ C6)
        GUITAR_MAP.put(72, KeyEvent.VK_Q); GUITAR_MAP.put(74, KeyEvent.VK_W); GUITAR_MAP.put(76, KeyEvent.VK_E);
        GUITAR_MAP.put(77, KeyEvent.VK_R); GUITAR_MAP.put(79, KeyEvent.VK_T); GUITAR_MAP.put(81, KeyEvent.VK_Y);
        GUITAR_MAP.put(83, KeyEvent.VK_U); GUITAR_MAP.put(84, KeyEvent.VK_I);
    }

    // 钢琴按键与修饰键数据结构
    private static class PianoKey {
        int mainKey;      // 主键 (例如 Q, W, E... 或 2, 3, 5...)
        int modifierKey;  // 修饰键 (0: 无, KeyEvent.VK_SHIFT: 升调高音, KeyEvent.VK_CONTROL: 降调低音)

        PianoKey(int mainKey, int modifierKey) {
            this.mainKey = mainKey;
            this.modifierKey = modifierKey;
        }
    }

    private static final Map<Integer, PianoKey> PIANO_MAP = new HashMap<>();
    static {
        // --- 1. 中音区 C4 ~ B4 (基础无修饰键) ---
        PIANO_MAP.put(60, new PianoKey(KeyEvent.VK_Q, 0)); // C4 (1)
        PIANO_MAP.put(61, new PianoKey(KeyEvent.VK_2, 0)); // #C4 (#1)
        PIANO_MAP.put(62, new PianoKey(KeyEvent.VK_W, 0)); // D4 (2)
        PIANO_MAP.put(63, new PianoKey(KeyEvent.VK_3, 0)); // #D4 (#2)
        PIANO_MAP.put(64, new PianoKey(KeyEvent.VK_E, 0)); // E4 (3)
        PIANO_MAP.put(65, new PianoKey(KeyEvent.VK_R, 0)); // F4 (4)
        PIANO_MAP.put(66, new PianoKey(KeyEvent.VK_5, 0)); // #F4 (#4)
        PIANO_MAP.put(67, new PianoKey(KeyEvent.VK_T, 0)); // G4 (5)
        PIANO_MAP.put(68, new PianoKey(KeyEvent.VK_6, 0)); // #G4 (#5)
        PIANO_MAP.put(69, new PianoKey(KeyEvent.VK_Y, 0)); // A4 (6)
        PIANO_MAP.put(70, new PianoKey(KeyEvent.VK_7, 0)); // #A4 (#6)
        PIANO_MAP.put(71, new PianoKey(KeyEvent.VK_U, 0)); // B4 (7)

        // --- 2. 高音区 C5 ~ B5 (按住 Shift 组合) ---
        PIANO_MAP.put(72, new PianoKey(KeyEvent.VK_Q, KeyEvent.VK_SHIFT)); // C5
        PIANO_MAP.put(73, new PianoKey(KeyEvent.VK_2, KeyEvent.VK_SHIFT));
        PIANO_MAP.put(74, new PianoKey(KeyEvent.VK_W, KeyEvent.VK_SHIFT));
        PIANO_MAP.put(75, new PianoKey(KeyEvent.VK_3, KeyEvent.VK_SHIFT));
        PIANO_MAP.put(76, new PianoKey(KeyEvent.VK_E, KeyEvent.VK_SHIFT));
        PIANO_MAP.put(77, new PianoKey(KeyEvent.VK_R, KeyEvent.VK_SHIFT));
        PIANO_MAP.put(78, new PianoKey(KeyEvent.VK_5, KeyEvent.VK_SHIFT));
        PIANO_MAP.put(79, new PianoKey(KeyEvent.VK_T, KeyEvent.VK_SHIFT));
        PIANO_MAP.put(80, new PianoKey(KeyEvent.VK_6, KeyEvent.VK_SHIFT));
        PIANO_MAP.put(81, new PianoKey(KeyEvent.VK_Y, KeyEvent.VK_SHIFT));
        PIANO_MAP.put(82, new PianoKey(KeyEvent.VK_7, KeyEvent.VK_SHIFT));
        PIANO_MAP.put(83, new PianoKey(KeyEvent.VK_U, KeyEvent.VK_SHIFT));

        // --- 3. 低音区 C3 ~ B3 (按住 Ctrl 组合) ---
        PIANO_MAP.put(48, new PianoKey(KeyEvent.VK_Q, KeyEvent.VK_CONTROL)); // C3
        PIANO_MAP.put(49, new PianoKey(KeyEvent.VK_2, KeyEvent.VK_CONTROL));
        PIANO_MAP.put(50, new PianoKey(KeyEvent.VK_W, KeyEvent.VK_CONTROL));
        PIANO_MAP.put(51, new PianoKey(KeyEvent.VK_3, KeyEvent.VK_CONTROL));
        PIANO_MAP.put(52, new PianoKey(KeyEvent.VK_E, KeyEvent.VK_CONTROL));
        PIANO_MAP.put(53, new PianoKey(KeyEvent.VK_R, KeyEvent.VK_CONTROL));
        PIANO_MAP.put(54, new PianoKey(KeyEvent.VK_5, KeyEvent.VK_CONTROL));
        PIANO_MAP.put(55, new PianoKey(KeyEvent.VK_T, KeyEvent.VK_CONTROL));
        PIANO_MAP.put(56, new PianoKey(KeyEvent.VK_6, KeyEvent.VK_CONTROL));
        PIANO_MAP.put(57, new PianoKey(KeyEvent.VK_Y, KeyEvent.VK_CONTROL));
        PIANO_MAP.put(58, new PianoKey(KeyEvent.VK_7, KeyEvent.VK_CONTROL));
        PIANO_MAP.put(59, new PianoKey(KeyEvent.VK_U, KeyEvent.VK_CONTROL));
    }

    public music() {
        try {
            robot = new Robot();
            sequencer = MidiSystem.getSequencer(false);
            sequencer.open();
        } catch (Exception e) {
            e.printStackTrace();
        }

        setTitle("《七日世界》吉他/钢琴双模式演奏器 (自动移调适配版)");
        setSize(540, 440);
        // 关闭动作在下方 windowClosing 中统一处理（需先释放修饰键）
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(10, 10));

        // 顶部面板 (模式切换 + 文件选择)
        JPanel topPanel = new JPanel(new GridLayout(4, 1, 5, 5));
        
        JPanel modePanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        modePanel.add(new JLabel("当前乐器模式: "));
        modeComboBox = new JComboBox<>(new String[]{"🎹 钢琴模式 (支持黑键/Shift/Ctrl)", "🎸 吉他模式 (22白键)"});
        modePanel.add(modeComboBox);
        
        autoAdaptCheckBox = new JCheckBox("自动适配音域/移调", true);
        selectFileButton = new JButton("📁 选择 MIDI 文件");
        fileLabel = new JLabel("未选择任何文件", SwingConstants.CENTER);
        
        topPanel.add(modePanel);
        topPanel.add(autoAdaptCheckBox);
        topPanel.add(selectFileButton);
        topPanel.add(fileLabel);
        add(topPanel, BorderLayout.NORTH);

        // 中间控制滑块区
        JPanel centerPanel = new JPanel(new GridLayout(3, 1, 5, 5));
        centerPanel.setBorder(BorderFactory.createTitledBorder("播放控制"));

        // 进度条
        JPanel progressPanel = new JPanel(new BorderLayout(5, 5));
        progressPanel.add(new JLabel("播放进度:"), BorderLayout.WEST);
        progressSlider = new JSlider(0, 100, 0);
        progressSlider.setEnabled(false);
        progressPanel.add(progressSlider, BorderLayout.CENTER);
        centerPanel.add(progressPanel);

        // 播放速度滑块 (10% 到 200%)
        JPanel speedPanel = new JPanel(new BorderLayout(5, 5));
        speedLabel = new JLabel("播放速度: 1.0x");
        speedPanel.add(speedLabel, BorderLayout.WEST);
        speedSlider = new JSlider(10, 200, 100);
        speedPanel.add(speedSlider, BorderLayout.CENTER);
        centerPanel.add(speedPanel);

        // 升降调滑块
        JPanel pitchPanel = new JPanel(new BorderLayout(5, 5));
        pitchLabel = new JLabel("升降调: 0 (原调)");
        pitchPanel.add(pitchLabel, BorderLayout.WEST);
        pitchSlider = new JSlider(-24, 24, 0);
        pitchSlider.setMajorTickSpacing(6);
        pitchSlider.setPaintTicks(true);
        pitchPanel.add(pitchSlider, BorderLayout.CENTER);
        centerPanel.add(pitchPanel);

        add(centerPanel, BorderLayout.CENTER);

        // 底部控制按钮
        JPanel bottomPanel = new JPanel(new GridLayout(2, 1, 5, 5));
        JPanel btnPanel = new JPanel(new FlowLayout());
        playButton = new JButton("▶ 开始");
        pauseButton = new JButton("⏸ 暂停");
        stopButton = new JButton("⏹ 停止");
        resetButton = new JButton("↺ 重置");
        resetButton.setToolTipText("恢复到刚打开程序的状态：清空已选文件、进度、速度与升降调");

        playButton.setEnabled(false);
        pauseButton.setEnabled(false);
        stopButton.setEnabled(false);

        btnPanel.add(playButton);
        btnPanel.add(pauseButton);
        btnPanel.add(stopButton);
        btnPanel.add(resetButton);

        statusLabel = new JLabel("状态: 请先选择 .mid 文件", SwingConstants.CENTER);
        bottomPanel.add(btnPanel);
        bottomPanel.add(statusLabel);
        add(bottomPanel, BorderLayout.SOUTH);

        // 事件绑定
        selectFileButton.addActionListener(e -> chooseFile());
        playButton.addActionListener(e -> startPlaying());
        pauseButton.addActionListener(e -> togglePause());
        stopButton.addActionListener(e -> stopPlaying());
        resetButton.addActionListener(e -> resetToInitialState());

        // 关窗即退出：先停播并释放修饰键，避免退出后 Shift/Ctrl 残留在按下状态
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                try {
                    if (isPlaying) stopPlaying();
                    else releaseAllModifiers();
                } finally {
                    dispose();
                    System.exit(0);
                }
            }
        });

        // 切换模式或勾选自动适配时，按当前模式重新计算最佳移调
        modeComboBox.addActionListener(e -> {
            if (autoAdaptCheckBox.isSelected() && sequence != null) autoAdaptPitch(sequence);
        });
        autoAdaptCheckBox.addActionListener(e -> {
            if (autoAdaptCheckBox.isSelected() && sequence != null) autoAdaptPitch(sequence);
        });

        speedSlider.addChangeListener(e -> {
            float factor = speedSlider.getValue() / 100.0f;
            if (factor < 0.1f) factor = 0.1f;
            speedLabel.setText(String.format("播放速度: %.1fx", factor));
            if (sequencer != null && sequencer.isOpen()) {
                sequencer.setTempoFactor(factor);
            }
        });

        pitchSlider.addChangeListener(e -> {
            int shift = pitchSlider.getValue();
            if (shift > 0) pitchLabel.setText("升降调: +" + shift + " (升高)");
            else if (shift < 0) pitchLabel.setText("升降调: " + shift + " (降低)");
            else pitchLabel.setText("升降调: 0 (原调)");

            // 用户手动拖动滑块即视为放弃自动适配，取消勾选避免“已勾选但移调已被手改”的假状态。
            // 自动适配自身调用 setValue 时置了 isAutoAdapting，这里顺带消费掉标志，不会误取消勾选。
            if (isAutoAdapting) {
                isAutoAdapting = false;
            } else if (autoAdaptCheckBox.isSelected()) {
                autoAdaptCheckBox.setSelected(false);
            }
        });

        progressSlider.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                isUserDraggingSlider = true;
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                if (sequencer != null && isPlaying) {
                    long targetTick = (long) (progressSlider.getValue() / 100.0 * sequencer.getTickLength());
                    sequencer.setTickPosition(targetTick);
                }
                isUserDraggingSlider = false;
            }
        });
    }

    private void chooseFile() {
        String lastDirPath = prefs.get(LAST_DIRECTORY_KEY, null);
        JFileChooser chooser = (lastDirPath != null && new File(lastDirPath).exists()) 
                ? new JFileChooser(lastDirPath) : new JFileChooser();

        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            // 如果正在播放，先自动停止，避免新旧播放的按键/Receiver 冲突
            if (isPlaying) {
                stopPlaying();
            }
            selectedMidiFile = chooser.getSelectedFile();
            fileLabel.setText("当前文件: " + selectedMidiFile.getName());
            playButton.setEnabled(true);
            prefs.put(LAST_DIRECTORY_KEY, selectedMidiFile.getParent());

            try {
                sequence = MidiSystem.getSequence(selectedMidiFile);
                sequencer.setSequence(sequence);
                
                // 自动分析曲子音域并智能调音
                autoAdaptPitch(sequence);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // 自动分析 MIDI 音高并调整到最佳移调
    private void autoAdaptPitch(Sequence seq) {
        if (!autoAdaptCheckBox.isSelected()) return;

        java.util.List<Integer> notes = new java.util.ArrayList<>();
        for (Track track : seq.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                MidiMessage msg = event.getMessage();
                if (msg instanceof ShortMessage) {
                    ShortMessage sm = (ShortMessage) msg;
                    if (sm.getCommand() == ShortMessage.NOTE_ON && sm.getData2() > 0 && sm.getChannel() != 9) {
                        notes.add(sm.getData1());
                    }
                }
            }
        }

        if (notes.isEmpty()) {
            statusLabel.setText("状态: 就绪，点击“开始”");
            return;
        }

        boolean isPianoMode = modeComboBox.getSelectedIndex() == 0;
        int bestShift;
        if (isPianoMode) {
            // 钢琴有完整 12 半音映射，直接对齐中音区中心 66 (F4 附近)
            long total = 0;
            for (int n : notes) total += n;
            bestShift = 66 - (int) (total / notes.size());
            if (bestShift > 24) bestShift = 24;
            if (bestShift < -24) bestShift = -24;
        } else {
            // 吉他只有 22 个白键：穷举 -12~+12 半音，选“精确命中白键最多”的移调，
            // 避免把整首曲子平移到黑键上，导致全部走近似匹配而大面积错音
            int bestCount = -1;
            bestShift = 0;
            for (int shift = -12; shift <= 12; shift++) {
                int hit = 0;
                for (int n : notes) {
                    int t = n + shift;
                    while (t < 48) t += 12;
                    while (t > 84) t -= 12;
                    if (GUITAR_MAP.containsKey(t)) hit++;
                }
                if (hit > bestCount || (hit == bestCount && Math.abs(shift) < Math.abs(bestShift))) {
                    bestCount = hit;
                    bestShift = shift;
                }
            }
        }

        isAutoAdapting = true;
        pitchSlider.setValue(bestShift);
        statusLabel.setText(String.format("已自动适配最佳音调 (移调: %+d)", bestShift));
    }

    /**
     * 无条件释放 Shift / Ctrl / Alt 并留出系统反应时间。
     * 停止、暂停、切换文件、关窗、播放异常等所有边界都要调用，避免修饰键残留在按下状态。
     * 每次调用都是幂等的：即使本来就没按下，多松一次也无副作用。
     */
    private void releaseAllModifiers() {
        synchronized (robot) {
            robot.keyRelease(KeyEvent.VK_SHIFT);
            robot.keyRelease(KeyEvent.VK_CONTROL);
            robot.keyRelease(KeyEvent.VK_ALT);
        }
        robot.delay(40); // 给游戏一点反应时间，确保音区状态归位
    }

    /**
     * 执行真实键盘模拟按键。
     *
     * 关键改动：修饰键不再跨音符保持。每个音符都是一次「按修饰键 → 按主键 → 松主键 →
     * 松修饰键」的独立动作，因此程序侧不存在“以为 Shift 还按着”的缓存状态。
     * 只要游戏收到了本音符的修饰键按下事件，音区就一定是正确的；即便某个事件偶发丢失，
     * 影响也只限于这一个音符，会自动恢复，不会像长按方案那样整段音区错位到曲终。
     */
    private void triggerKeyPress(PianoKey pKey) {
        synchronized (robot) {
            if (pKey.modifierKey != 0) {
                robot.keyPress(pKey.modifierKey);
                robot.delay(10); // 留出 10ms 给游戏切换音区
            }
            robot.keyPress(pKey.mainKey);
            robot.delay(8);
            robot.keyRelease(pKey.mainKey);
            if (pKey.modifierKey != 0) {
                robot.delay(6);
                robot.keyRelease(pKey.modifierKey);
            }
        }
    }

    /**
     * 限流：仅当 MIN_NOTE_GAP_MS > 0 时生效，用于极少数用户手动开启的场景
     * （例如机器性能不足或游戏输入明显丢键）。默认关闭，因为限流会砍掉齐奏和弦。
     * 返回 true 表示可以投递。
     */
    private boolean allowDelivery() {
        if (MIN_NOTE_GAP_MS <= 0) return true;
        long now = System.nanoTime();
        if (now - lastNoteNanos < MIN_NOTE_GAP_MS * 1_000_000L) {
            droppedNotes++;
            return false;
        }
        lastNoteNanos = now;
        return true;
    }    /**
     * 积压检测：把"谱面应有的时长"与"实际播放耗时"对比。
     *
     * 为什么不逐音符算：实测本机 JVM 上 Receiver.send() 的 timeStamp 恒为 -1
     * （RealTimeSequencer 不填充该参数），无法据此判断派发是否落后；而
     * sequencer.getMicrosecondPosition() 只反映播放位置，也不反映积压。
     * 因此改用端到端耗时核算——若程序跟不上谱面，整曲实际耗时会明显长于谱面长度。
     */
    private void recordExpectedDuration() {
        if (sequencer == null || !sequencer.isOpen()) return;
        long tickLen = sequencer.getTickLength();
        if (tickLen <= 0) return;
        double factor = Math.max(speedSlider.getValue() / 100.0, 0.1);
        playbackExpectedMs = (long) (sequencer.getMicrosecondLength() / 1000.0 / factor);
    }

    // 处理吉他模式按键
    private Integer adaptGuitarNote(int rawNote) {
        int noteWithOffset = rawNote + pitchSlider.getValue();
        // 1. 精确匹配
        if (GUITAR_MAP.containsKey(noteWithOffset)) {
            return GUITAR_MAP.get(noteWithOffset);
        }
        // 2. 循环八度匹配（保持在 48~84 范围内）
        int adjusted = noteWithOffset;
        while (adjusted < 48) adjusted += 12;
        while (adjusted > 84) adjusted -= 12;
        if (GUITAR_MAP.containsKey(adjusted)) {
            return GUITAR_MAP.get(adjusted);
        }
        // 3. 找最近的白键（±1 或 ±2 半音）
        for (int offset : new int[]{1, -1, 2, -2}) {
            int candidate = adjusted + offset;
            if (GUITAR_MAP.containsKey(candidate)) {
                return GUITAR_MAP.get(candidate);
            }
        }
        // 4. 实在找不到就忽略（返回 null）
        return null;
    }

    // 处理钢琴模式按键
    private PianoKey adaptPianoNote(int rawNote) {
        int noteWithOffset = rawNote + pitchSlider.getValue();
        if (PIANO_MAP.containsKey(noteWithOffset)) {
            return PIANO_MAP.get(noteWithOffset);
        }

        // 强制折叠到 48~83 音域（含 ≤0 的极端低音，向上补足完整八度）
        int adjusted = noteWithOffset;
        while (adjusted < 48) adjusted += 12;
        while (adjusted > 83) adjusted -= 12;

        if (PIANO_MAP.containsKey(adjusted)) {
            return PIANO_MAP.get(adjusted);
        }
        // 如果遇到未定义的半音，降半音强行保底弹出来（保证绝不丢音）
        return PIANO_MAP.getOrDefault(adjusted - 1, PIANO_MAP.get(60));
    }

    // 在 EDT 线程安全更新状态栏，避免后台线程直接改 UI
    private void setStatus(String text) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(text));
    }

    private void startPlaying() {
        if (selectedMidiFile == null) return;

        // 每次播放前强制释放所有可能残留的修饰键，避免上一首残留状态影响
        releaseAllModifiers();
        droppedNotes = 0;

        isPlaying = true;
        isPaused = false;

        // 重置按钮在播放期间禁用：必须先停止再重置，避免"边弹边清"的中间态
        setControlsEnabled(true);

        boolean isPianoMode = modeComboBox.getSelectedIndex() == 0;

        new Thread(() -> {
            try {
                if (sequencer.getTickPosition() == 0) {
                    for (int i = 3; i > 0; i--) {
                        if (!isPlaying) return;
                        setStatus("状态: " + i + " 秒后开始，请切回游戏！");
                        Thread.sleep(1000);
                    }
                }

                setStatus("状态: 正在自动弹奏中...");

                sequencer.getTransmitter().setReceiver(new Receiver() {
                    @Override
                    public void send(MidiMessage message, long timeStamp) {
                        if (!isPlaying || isPaused) return;
                        if (message instanceof ShortMessage) {
                            ShortMessage sm = (ShortMessage) message;
                            if (sm.getChannel() == 9) return; // 忽略鼓点

                            if (sm.getCommand() == ShortMessage.NOTE_ON && sm.getData2() > 0) {
                                int rawNote = sm.getData1();

                                if (isPianoMode) {
                                    PianoKey pKey = adaptPianoNote(rawNote);
                                    if (pKey != null && allowDelivery()) {
                                        if (DEBUG) System.out.println("[MIDI] 原始=" + rawNote + " 主键=" + pKey.mainKey + " 修饰=" + pKey.modifierKey);
                                        triggerKeyPress(pKey);
                                    }
                                } else {
                                    Integer vkCode = adaptGuitarNote(rawNote);
                                    if (vkCode != null && allowDelivery()) {
                                        if (DEBUG) System.out.println("[MIDI] 原始=" + rawNote + " 按键=" + vkCode);
                                        // 吉他不用修饰键，但仍需先释放，防止从钢琴模式切过来时残留
                                        synchronized (robot) {
                                            robot.keyRelease(KeyEvent.VK_SHIFT);
                                            robot.keyRelease(KeyEvent.VK_CONTROL);
                                            robot.keyPress(vkCode);
                                            try { Thread.sleep(15); } catch (InterruptedException ignored) {}
                                            robot.keyRelease(vkCode);
                                        }
                                    }
                                }
                            }
                        }
                    }
                    @Override
                    public void close() {}
                });

                sequencer.setTempoFactor(speedSlider.getValue() / 100.0f);
                recordExpectedDuration();
                long wallStart = System.currentTimeMillis();
                sequencer.start();

                while (isPlaying) {
                    if (isPaused) {
                        Thread.sleep(150);
                        continue;
                    }
                    if (!isUserDraggingSlider && sequencer.getTickLength() > 0) {
                        int progress = (int) ((double) sequencer.getTickPosition() / sequencer.getTickLength() * 100);
                        SwingUtilities.invokeLater(() -> progressSlider.setValue(progress));
                    }
                    if (!sequencer.isRunning()) {
                        break; // 播放自然结束
                    }
                    Thread.sleep(150);
                }

                if (isPlaying && !isPaused) {
                    playbackActualMs = System.currentTimeMillis() - wallStart;
                    // 曲终必须无条件释放修饰键；若实际耗时明显超过谱面长度，说明程序没跟上
                    final int dropped = droppedNotes;
                    final long lagMs = playbackActualMs - playbackExpectedMs;
                    SwingUtilities.invokeLater(() -> {
                        stopPlaying();
                        StringBuilder sb = new StringBuilder("状态: 演奏完毕");
                        if (MIN_NOTE_GAP_MS > 0 && dropped > 0) {
                            sb.append(String.format("（限流丢弃 %d 个过密音符）", dropped));
                        }
                        if (lagMs >= 150) {
                            sb.append(String.format("（比谱面慢了 %d ms，可尝试降低播放速度）", lagMs));
                        }
                        statusLabel.setText(sb.toString());
                    });
                }

            } catch (Exception e) {
                setStatus("错误: 播放过程出现异常！");
                e.printStackTrace();
            }
        }).start();
    }

    private void togglePause() {
        if (!isPlaying) return;

        if (isPaused) {
            isPaused = false;
            lastNoteNanos = 0L; // 复位限流基准，避免恢复后的第一个音符被误丢弃
            sequencer.start();
            pauseButton.setText("⏸ 暂停");
            statusLabel.setText("状态: 继续弹奏中...");
        } else {
            isPaused = true;
            sequencer.stop();
            releaseAllModifiers();
            pauseButton.setText("▶ 继续");
            statusLabel.setText("状态: 已暂停，可随时拖动进度条");
        }
    }

    private void stopPlaying() {
        isPlaying = false;
        isPaused = false;
        if (sequencer != null && sequencer.isOpen()) {
            sequencer.stop();
            sequencer.setTickPosition(0);
        }
        releaseAllModifiers();
        progressSlider.setValue(0);
        progressSlider.setEnabled(false);

        setControlsEnabled(false);
        pauseButton.setText("⏸ 暂停");
        statusLabel.setText("状态: 已停止");
    }

    /** 统一管理按钮可用状态，避免各流程各写一份导致状态不一致。 */
    private void setControlsEnabled(boolean playing) {
        playButton.setEnabled(!playing && selectedMidiFile != null);
        selectFileButton.setEnabled(!playing);
        resetButton.setEnabled(!playing);
        pauseButton.setEnabled(playing);
        stopButton.setEnabled(playing);
    }

    /**
     * 重置：把演奏器恢复到"刚打开程序"的状态。
     * 清空已选文件与序列、归零进度、速度回到 1.0x、升降调回到原调、模式回到钢琴模式、
     * 自动适配恢复默认勾选，并释放全部修饰键。不涉及主窗口尺寸与上次目录等持久化设置。
     */
    private void resetToInitialState() {
        // 先停播（内部会停止 Sequencer、释放修饰键、复位进度条）
        if (isPlaying) stopPlaying();

        selectedMidiFile = null;
        sequence = null;
        fileLabel.setText("未选择任何文件");

        // 清空 Sequencer 中驻留的上一首曲子，避免残留状态影响下一首
        // （实测：传 null 可安全清空，且之后 getTickLength/getTickPosition/setTickPosition/stop
        //   均正常；唯一不可调用的是 start()，而重置流程不会触发它）
        if (sequencer != null && sequencer.isOpen()) {
            try {
                sequencer.setSequence((Sequence) null);
            } catch (InvalidMidiDataException ignored) {
                // 传 null 清空不会产生非法数据；仅为满足受检异常而捕获
            }
        }

        // 这些 setValue 会触发各自的 ChangeListener，标签文字与速度因子随之复位
        speedSlider.setValue(100);
        pitchSlider.setValue(0);

        modeComboBox.setSelectedIndex(0);
        autoAdaptCheckBox.setSelected(true);
        isAutoAdapting = false;

        progressSlider.setValue(0);
        progressSlider.setEnabled(false);

        setControlsEnabled(false);
        pauseButton.setText("⏸ 暂停");
        statusLabel.setText("状态: 已重置，请先选择 .mid 文件");
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new music().setVisible(true));
    }
}