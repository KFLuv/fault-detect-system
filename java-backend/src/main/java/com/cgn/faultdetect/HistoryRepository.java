package com.cgn.faultdetect;

import com.cgn.faultdetect.Knowledge.Scenario;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SQLite 历史记录 + 自定义场景持久化
 * 数据目录：环境变量 FAULT_DETECT_DATA_DIR 指定，默认 ./data（对应 Python 版行为）
 */
@Component
public class HistoryRepository {

    private final Path dataDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Scenario> customScenarios = new ArrayList<>();
    private final Path customFile;

    public HistoryRepository() {
        String env = System.getenv("FAULT_DETECT_DATA_DIR");
        String dir = (env == null || env.trim().isEmpty())
                ? new File("data").getAbsolutePath() : env.trim();
        dataDir = Path.of(dir);
        try {
            Files.createDirectories(dataDir);
        } catch (Exception e) {
            throw new IllegalStateException("无法创建数据目录: " + dataDir, e);
        }
        customFile = dataDir.resolve("custom_scenarios.json");
        loadCustomScenarios();
        initDb();
    }

    // ================= 自定义场景 =================

    private void loadCustomScenarios() {
        if (!Files.exists(customFile)) {
            return;
        }
        try {
            String json = Files.readString(customFile);
            List<Scenario> list = mapper.readValue(json,
                    mapper.getTypeFactory().constructCollectionType(List.class, Scenario.class));
            customScenarios.clear();
            if (list != null) {
                customScenarios.addAll(list);
            }
        } catch (Exception e) {
            customScenarios.clear();
        }
    }

    private void saveCustomScenarios() {
        try {
            Files.writeString(customFile,
                    mapper.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(customScenarios));
        } catch (Exception e) {
            throw new IllegalStateException("保存自定义场景失败", e);
        }
    }

    public List<Scenario> getCustomScenarios() {
        return new ArrayList<>(customScenarios);
    }

    public void addCustomScenario(Scenario sc) {
        customScenarios.add(sc);
        saveCustomScenarios();
    }

    // ================= SQLite 历史 =================

    private Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dataDir.resolve("fault_detect.db"));
    }

    private void initDb() {
        try (Connection conn = open();
             Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS detect_history ("
                    + "id TEXT PRIMARY KEY,"
                    + "ts TEXT,"
                    + "url TEXT,"
                    + "status_code TEXT,"
                    + "root_cause TEXT,"
                    + "scenario_name TEXT,"
                    + "confidence REAL,"
                    + "report TEXT"
                    + ")");
        } catch (SQLException e) {
            throw new IllegalStateException("初始化数据库失败", e);
        }
    }

    public void saveHistory(String url, String normalizedStatus, Map<String, Object> conclusion) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        String rootCause = String.valueOf(conclusion.getOrDefault("root_cause_label", ""));
        String scenarioName = String.valueOf(conclusion.getOrDefault("scenario_name", ""));
        Object confObj = conclusion.get("confidence");
        double confidence = confObj instanceof Number ? ((Number) confObj).doubleValue() : 0.0;
        String reportJson;
        try {
            reportJson = mapper.writeValueAsString(conclusion);
        } catch (Exception e) {
            reportJson = "{}";
        }
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO detect_history (id, ts, url, status_code, root_cause, scenario_name, confidence, report) "
                             + "VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, ts);
            ps.setString(3, url);
            ps.setString(4, normalizedStatus);
            ps.setString(5, rootCause);
            ps.setString(6, scenarioName);
            ps.setDouble(7, confidence);
            ps.setString(8, reportJson);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("保存历史失败", e);
        }
    }

    public void clearHistory() {
        try (Connection conn = open();
             Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM detect_history");
        } catch (SQLException e) {
            throw new IllegalStateException("清空历史失败", e);
        }
    }

    public List<Map<String, Object>> listHistory(int limit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String sql = "SELECT id, ts, url, status_code, root_cause, scenario_name, confidence "
                + "FROM detect_history ORDER BY ts DESC LIMIT ?";
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getString("id"));
                    row.put("ts", rs.getString("ts"));
                    row.put("url", rs.getString("url"));
                    row.put("status_code", rs.getString("status_code"));
                    row.put("root_cause", rs.getString("root_cause"));
                    row.put("scenario_name", rs.getString("scenario_name"));
                    row.put("confidence", rs.getDouble("confidence"));
                    rows.add(row);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询历史失败", e);
        }
        return rows;
    }
}
