import javax.sound.midi.*;
import java.io.*;
import java.util.*;

/**
 * 吉他模式适配度批量评估工具
 *
 * 用法（在项目根目录执行）：
 *   javac -encoding UTF-8 GuitarFit.java
 *   java GuitarFit                          # 评估 midi/ 下全部曲目
 *   java GuitarFit 我的新歌目录              # 评估指定目录
 *   java GuitarFit 我的新歌目录 报告.txt      # 顺便指定报告文件名
 *
 * 它使用与 music.java 中 adaptGuitarNote / autoAdaptPitch 完全一致的算法，
 * 预测每首曲子切到"吉他模式"后实际会弹出什么音，因此可直接用于选曲：
 * 把下载的 .mid 丢进一个文件夹，跑一遍就知道值不值得用吉他模式弹。
 *
 * 本文件是独立的辅助工具，不影响主程序运行。
 */
public class GuitarFit {

    static final Set<Integer> WHITE = new HashSet<>(Arrays.asList(
            48, 50, 52, 53, 55, 57, 59, 60, 62, 64, 65, 67, 69, 71,
            72, 74, 76, 77, 79, 81, 83, 84));

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : "midi";
        String report = args.length > 1 ? args[1] : "吉他适配度报告.txt";

        List<File> files = new ArrayList<>();
        collect(new File(dir), files);
        Collections.sort(files);
        if (files.isEmpty()) {
            System.out.println("在 " + dir + " 下没找到 .mid 文件");
            return;
        }

        PrintStream out = new PrintStream(new FileOutputStream(report), true, "UTF-8");
        List<Object[]> rows = new ArrayList<>(); // {名称, 精确率, 和谐率, 近似率, 移调, 用键数, 换把, 音符数}

        for (File f : files) {
            try {
                Sequence seq = MidiSystem.getSequence(f);
                List<Integer> notes = new ArrayList<>();
                for (Track t : seq.getTracks())
                    for (int i = 0; i < t.size(); i++) {
                        MidiMessage msg = t.get(i).getMessage();
                        if (!(msg instanceof ShortMessage)) continue;
                        ShortMessage sm = (ShortMessage) msg;
                        if (sm.getCommand() != ShortMessage.NOTE_ON || sm.getData2() <= 0) continue;
                        if (sm.getChannel() == 9) continue;
                        notes.add(sm.getData1());
                    }
                if (notes.size() < 20) continue; // 音符太少不足以评价

                int shift = bestShift(notes);
                int exact = 0, oct = 0, near = 0, drop = 0, switches = 0, last = -1;
                Set<Integer> used = new TreeSet<>();
                for (int n : notes) {
                    int c = classify(n, shift);
                    if (c == 0) exact++;
                    else if (c == 1) oct++;
                    else if (c == 2) near++;
                    else drop++;
                    int t = fold(n + shift);
                    if (!WHITE.contains(t)) {
                        for (int off : new int[]{1, -1, 2, -2})
                            if (WHITE.contains(t + off)) { t += off; break; }
                    }
                    used.add(t);
                    int zone = t < 60 ? 0 : (t < 72 ? 1 : 2);
                    if (last != -1 && zone != last) switches++;
                    last = zone;
                }
                int total = notes.size();
                rows.add(new Object[]{f.getName(),
                        exact * 100.0 / total,
                        (exact + oct) * 100.0 / total,
                        (near + drop) * 100.0 / total,
                        shift, used.size(), switches, total});
            } catch (Exception e) {
                out.println("解析失败: " + f.getName() + " -> " + e.getMessage());
            }
        }

        rows.sort((a, b) -> {
            double ha = (Double) a[2], hb = (Double) b[2];
            if (Math.abs(ha - hb) > 0.001) return Double.compare(hb, ha);
            return Double.compare((Double) b[1], (Double) a[1]);
        });

        out.println("吉他模式适配度报告    评估目录: " + new File(dir).getAbsolutePath());
        out.println("评估时间: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        out.println();
        out.println("音高正确率 = 精确命中白键 + 仅被换八度（这两个都是正确的音）");
        out.println("近似错音率 = 被迫用差 1~2 半音的白键顶替（这部分听感是错的）");
        out.println("换把次数   = 低/中/高音区之间来回跳的次数，越多越难弹");
        out.println("移调       = 程序自动适配后采用的半音移调量");
        out.println();
        out.printf("%-4s %-34s %8s %8s %8s %7s %7s %8s%n",
                "排名", "文件", "音高正确", "其中精确", "近似错音", "换把", "用键数", "移调");
        out.println("-".repeat(96));
        int rank = 1;
        for (Object[] r : rows) {
            out.printf("%-4d %-34s %7.1f%% %7.1f%% %7.1f%% %7d %7d %+7d%n",
                    rank++, trim((String) r[0]), (Double) r[2], (Double) r[1],
                    (Double) r[3], (Integer) r[6], (Integer) r[5], (Integer) r[4]);
        }
        out.println("-".repeat(96));
        out.println();
        out.println("怎么看：音高正确率 ≥ 97% 且 近似错音率 ≤ 3% 的曲子，吉他模式听着基本没问题；");
        out.println("        换把次数 < 300 的属于轻松曲目，> 800 的需要频繁跳音区，容易手忙脚乱。");
        out.println("        同一个白键被按下时，低/中/高音区由不同行键盘（Z-M / A-J / Q-I）表达，");
        out.println("        所以换把次数才是真正的演奏难度。");
        out.close();

        System.out.println("已评估 " + rows.size() + " 首，报告写入: " + new File(report).getAbsolutePath());
        if (!rows.isEmpty()) {
            Object[] top = rows.get(0);
            System.out.println("最佳: " + top[0] + "  音高正确 " + String.format("%.1f%%", (Double) top[2]));
        }
    }

    static int classify(int rawNote, int shift) {
        int n = rawNote + shift;
        if (WHITE.contains(n)) return 0;
        int a = fold(n);
        if (WHITE.contains(a)) return 1;
        for (int off : new int[]{1, -1, 2, -2}) if (WHITE.contains(a + off)) return 2;
        return 3;
    }

    static int fold(int n) {
        int a = n;
        while (a < 48) a += 12;
        while (a > 84) a -= 12;
        return a;
    }

    /** 与 music.java autoAdaptPitch 的吉他分支一致 */
    static int bestShift(List<Integer> notes) {
        int bestCount = -1, bestShift = 0;
        for (int shift = -12; shift <= 12; shift++) {
            int hit = 0;
            for (int n : notes) if (WHITE.contains(fold(n + shift))) hit++;
            if (hit > bestCount || (hit == bestCount && Math.abs(shift) < Math.abs(bestShift))) {
                bestCount = hit;
                bestShift = shift;
            }
        }
        return bestShift;
    }

    static void collect(File d, List<File> out) {
        File[] fs = d.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) collect(f, out);
            else if (f.getName().toLowerCase().endsWith(".mid")
                    || f.getName().toLowerCase().endsWith(".midi")) out.add(f);
        }
    }

    static String trim(String s) { return s.length() > 32 ? s.substring(0, 31) + "…" : s; }
}
