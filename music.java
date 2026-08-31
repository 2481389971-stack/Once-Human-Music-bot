import javax.swing.*;
import javax.sound.midi.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.prefs.Preferences;

public class music extends JFrame {
    private JButton selectFileButton, playButton, pauseButton, stopButton;
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
    private boolean isUserDraggingSlider = false;

    // 记录当前保持按下的修饰键状态 (0: 无, VK_SHIFT, VK_CONTROL)
    private int activeModifier = 0;

    // 调试日志开关：排查错音时置为 true，定位后可改回 false
    private static final boolean DEBUG = true;

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
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
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

        playButton.setEnabled(false);
        pauseButton.setEnabled(false);
        stopButton.setEnabled(false);

        btnPanel.add(playButton);
        btnPanel.add(pauseButton);
        btnPanel.add(stopButton);

        statusLabel = new JLabel("状态: 请先选择 .mid 文件", SwingConstants.CENTER);
        bottomPanel.add(btnPanel);
        bottomPanel.add(statusLabel);
        add(bottomPanel, BorderLayout.SOUTH);

        // 事件绑定
        selectFileButton.addActionListener(e -> chooseFile());
        playButton.addActionListener(e -> startPlaying());
        pauseButton.addActionListener(e -> togglePause());
        stopButton.addActionListener(e -> stopPlaying());

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

        long totalPitch = 0;
        int count = 0;
        for (Track track : seq.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                MidiMessage msg = event.getMessage();
                if (msg instanceof ShortMessage) {
                    ShortMessage sm = (ShortMessage) msg;
                    if (sm.getCommand() == ShortMessage.NOTE_ON && sm.getData2() > 0 && sm.getChannel() != 9) {
                        totalPitch += sm.getData1();
                        count++;
                    }
                }
            }
        }

        if (count > 0) {
            int avgPitch = (int) (totalPitch / count);
            // 钢琴中音区中心 MIDI 值是 66 (F4 左右)
            int bestShift = 66 - avgPitch;
            pitchSlider.setValue(bestShift);
            statusLabel.setText(String.format("已自动适配最佳音调 (移调: %+d)", bestShift));
        } else {
            statusLabel.setText("状态: 就绪，点击“开始”");
        }
    }

    // 释放当前可能按住的修饰键
    private void releaseActiveModifier() {
        if (activeModifier != 0) {
            robot.keyRelease(activeModifier);
            activeModifier = 0;
            robot.delay(10);
        }
    }
    // 执行真实键盘模拟按键（持续修饰键优化）
    private void triggerKeyPress(PianoKey pKey) {
        synchronized (robot) {
            // 1. 如果需要的修饰键与当前按住的不一致，更新修饰键状态
            if (pKey.modifierKey != activeModifier) {
                releaseActiveModifier();
                if (pKey.modifierKey != 0) {
                    robot.keyPress(pKey.modifierKey);
                    activeModifier = pKey.modifierKey;
                    robot.delay(10); // 留出 10ms 给游戏切调响应
                }
            }
            // 2. 按下并释放主音符键
            robot.keyPress(pKey.mainKey);
            robot.delay(8);
            robot.keyRelease(pKey.mainKey);
        }
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

        // 强制折叠到 48~83 音域
        int adjusted = noteWithOffset;
        while (adjusted < 48 && adjusted > 0) adjusted += 12;
        while (adjusted > 83) adjusted -= 12;

        if (PIANO_MAP.containsKey(adjusted)) {
            return PIANO_MAP.get(adjusted);
        }
        // 如果遇到未定义的半音，降半音强行保底弹出来（保证绝不丢音）
        return PIANO_MAP.getOrDefault(adjusted - 1, PIANO_MAP.get(60));
    }

    private void startPlaying() {
        if (selectedMidiFile == null) return;

        // 每次播放前强制释放所有可能残留的修饰键，避免上一首残留状态影响
        releaseActiveModifier();
        robot.keyRelease(KeyEvent.VK_SHIFT);
        robot.keyRelease(KeyEvent.VK_CONTROL);
        robot.keyRelease(KeyEvent.VK_ALT);
        activeModifier = 0;

        isPlaying = true;
        isPaused = false;

        playButton.setEnabled(false);
        selectFileButton.setEnabled(false);
        pauseButton.setEnabled(true);
        stopButton.setEnabled(true);
        progressSlider.setEnabled(true);

        boolean isPianoMode = modeComboBox.getSelectedIndex() == 0;

        new Thread(() -> {
            try {
                if (sequencer.getTickPosition() == 0) {
                    for (int i = 3; i > 0; i--) {
                        if (!isPlaying) return;
                        statusLabel.setText("状态: " + i + " 秒后开始，请切回游戏！");
                        Thread.sleep(1000);
                    }
                }

                statusLabel.setText("状态: 正在自动弹奏中...");

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
                                    if (pKey != null) {
                                        if (DEBUG) System.out.println("[MIDI] 原始=" + rawNote + " 主键=" + pKey.mainKey + " 修饰=" + pKey.modifierKey);
                                        triggerKeyPress(pKey);
                                    }
                                } else {
                                    releaseActiveModifier();
                                    Integer vkCode = adaptGuitarNote(rawNote);
                                    if (vkCode != null) {
                                        if (DEBUG) System.out.println("[MIDI] 原始=" + rawNote + " 按键=" + vkCode);
                                        synchronized (robot) {
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
                sequencer.start();

                while (sequencer.isRunning() && isPlaying) {
                    if (!isUserDraggingSlider && sequencer.getTickLength() > 0) {
                        int progress = (int) ((double) sequencer.getTickPosition() / sequencer.getTickLength() * 100);
                        progressSlider.setValue(progress);
                    }
                    Thread.sleep(150);
                }

                if (!isPaused && isPlaying) {
                    stopPlaying();
                    statusLabel.setText("状态: 演奏完毕");
                }

            } catch (Exception e) {
                statusLabel.setText("错误: 播放过程出现异常！");
                e.printStackTrace();
            }
        }).start();
    }

    private void togglePause() {
        if (!isPlaying) return;

        if (isPaused) {
            isPaused = false;
            sequencer.start();
            pauseButton.setText("⏸ 暂停");
            statusLabel.setText("状态: 继续弹奏中...");
        } else {
            isPaused = true;
            sequencer.stop();
            synchronized (robot) {
                releaseActiveModifier();
            }
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
        synchronized (robot) {
            releaseActiveModifier();
            // 额外强制释放修饰键，确保完全松开
            robot.keyRelease(KeyEvent.VK_SHIFT);
            robot.keyRelease(KeyEvent.VK_CONTROL);
            robot.keyRelease(KeyEvent.VK_ALT);
        }
        robot.delay(50); // 给系统一点反应时间
        progressSlider.setValue(0);
        progressSlider.setEnabled(false);

        playButton.setEnabled(true);
        selectFileButton.setEnabled(true);
        pauseButton.setEnabled(false);
        pauseButton.setText("⏸ 暂停");
        stopButton.setEnabled(false);
        statusLabel.setText("状态: 已停止");
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new music().setVisible(true));
    }
}