package dev.dae.software.app;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.swing.UIManager;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * @see https://golb.hplar.ch/2019/08/json-p.html
 * @author Ben
 */
public class MainFrame extends javax.swing.JFrame {

    private static final Path CONFIG_PATH = Paths.get(System.getProperty("user.home"), ".tenant-api.json");
    private static final String CIPHER_METHOD = "AES/CBC/PKCS7Padding";
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final JsonObject defaultCconfigObject = Json.createObjectBuilder()
            .add("session", Json.createObjectBuilder()
                    .add("session-id", "")
                    .add("updated-at", "")
                    .add("expired-at", 0))
            .build();
    private final MqttConnectOptions mqttConnectionOptions = new MqttConnectOptions();
    private MqttClient mqttClient;
    private JsonObject configObject;
    private JsonObject sessionObject;
    private JsonObject loginDataObject;
    private JsonObject mqttObject;
    private JsonObject channelsObject;
    private String topicPrefix;
    private String keySeed;
    private int tenantId;

    private void setTextAreaDefaultFont() {
        if (Arrays.stream(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.TRADITIONAL_CHINESE)).anyMatch(s -> s.equals("Microsoft YaHei Mono"))) {
            jtaStatus.setFont(new java.awt.Font("Microsoft YaHei Mono", 0, 12));
        } else {
            try {
                Font font = Font.createFont(Font.TRUETYPE_FONT, MainFrame.class.getResourceAsStream("/META-INF/MicrosoftYaHeiMono-CP950.ttf"));
                jtaStatus.setFont(font.deriveFont(12f));
            } catch (FontFormatException | IOException ex) {
                Logger.getLogger(MainFrame.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }
    
    private synchronized void resetSessionId(boolean isNew) {
        try {
            Files.writeString(CONFIG_PATH, defaultCconfigObject.toString(), isNew ? StandardOpenOption.CREATE : StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ex) {
            Logger.getLogger(MainFrame.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    private synchronized void updateSessionId(String sessionId) {
        if (Objects.nonNull(sessionId) && !sessionId.isEmpty()) {
            Map<String, String> map = Arrays.stream(sessionId.split(";\\s?")).map(s -> s.split("=")).collect(Collectors.toMap(s -> s[0], s -> s[1]));
            configObject = Json.createPointer("/session/session-id").replace(configObject, Json.createValue(map.get("PHPSESSID")));
            configObject = Json.createPointer("/session/updated-at").replace(configObject, Json
                    .createValue(Instant.now().atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_ZONED_DATE_TIME)));
            if (map.containsKey("Max-Age")) {
                Json.createPointer("/session/expired-at").replace(configObject, Json.createValue(Instant.now().plusSeconds(Integer.parseInt(map.get("Max-Age"))).toEpochMilli()));
            }
            
            sessionObject = configObject.getJsonObject("session");
            try {
                Files.writeString(CONFIG_PATH, configObject.toString(), StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException ex) {
                Logger.getLogger(MainFrame.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }

    /**
     * 執行房客 API 之 HTTP 請求
     *
     * @param bodyMap
     * @return HTTP 請求後的 Response 物件之 Optional 物件
     */
    private Optional<HttpResponse<String>> doRequest(HashMap<String, String> bodyMap) {
        HttpResponse<String> response = null;

        String uri = String.format("%s://%s%s", jcbProtocol.getSelectedItem(), jcbHost.getSelectedItem(), jtfPath.getText());
        String bodyContent = bodyMap.entrySet()
                .stream()
                .map(entry -> String.format("%s=%s", URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8), URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)))
                .collect(Collectors.joining("&"));

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(uri))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:94.0) Gecko/20100101 Firefox/94.0")
                .POST(HttpRequest.BodyPublishers.ofString(bodyContent));

        String sessionId = sessionObject.getString("session-id", "").isBlank() ? "" : String.format("PHPSESSID=%s", sessionObject.getString("session-id"));
        System.out.println("sessionId = " + sessionId);
        HttpRequest request = sessionId.isEmpty() ? requestBuilder.build() : requestBuilder.copy().header("Cookie", sessionId).build();

        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
            HttpHeaders headers = response.headers();
            Optional<String> setCookieOptional = headers.firstValue("set-cookie");
            updateSessionId(setCookieOptional.orElse(""));
        } catch (IOException | InterruptedException ex) {
            System.err.printf("%tY/%<tm/%<td %<tp %<tI:%<tM:%<tS - %s throws %s: %s%n", System.currentTimeMillis(), MainFrame.class.getName(), ex.getClass().getName(), ex.getMessage());
        }
        return Optional.ofNullable(response);
    }

    /**
     * Creates new form MainFrame
     */
    public MainFrame() {
        initComponents();
        setTextAreaDefaultFont();
        mqttConnectionOptions.setAutomaticReconnect(true);

        try {
            if (Files.notExists(CONFIG_PATH) || Files.readString(CONFIG_PATH, StandardCharsets.UTF_8).isBlank()) {
                resetSessionId(true);
            }
            String configString = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            try (JsonReader reader = Json.createReader(new StringReader(configString))) {
                configObject = reader.readObject();
            }
            sessionObject = configObject.getJsonObject("session");
        } catch (IOException ex) {
            Logger.getLogger(MainFrame.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    /**
     * This method is called from within the constructor to initialize the form. WARNING: Do NOT modify this code. The content of this method is always regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        jScrollPane1 = new javax.swing.JScrollPane();
        jtaStatus = new javax.swing.JTextArea();
        jbClearTextArea = new javax.swing.JButton();
        jtpServicePane = new javax.swing.JTabbedPane();
        jpShare = new javax.swing.JPanel();
        jLabel1 = new javax.swing.JLabel();
        jtfAccount = new javax.swing.JTextField();
        jlbHost = new javax.swing.JLabel();
        jcbHost = new javax.swing.JComboBox<>();
        jlbProtocol = new javax.swing.JLabel();
        jcbProtocol = new javax.swing.JComboBox<>();
        jlbPath = new javax.swing.JLabel();
        jtfPath = new javax.swing.JTextField();
        jpLogin = new javax.swing.JPanel();
        jlbPassword = new javax.swing.JLabel();
        jlbUserCode = new javax.swing.JLabel();
        jlbLangCode = new javax.swing.JLabel();
        jcbLangCode = new javax.swing.JComboBox<>();
        jlbAppId = new javax.swing.JLabel();
        jtfAppId = new javax.swing.JTextField();
        jlbAppVersion = new javax.swing.JLabel();
        jtfAppVersion = new javax.swing.JTextField();
        jtfUserCode = new javax.swing.JTextField();
        jpfPassword = new javax.swing.JPasswordField();
        jbLogin = new javax.swing.JButton();
        jbLogout = new javax.swing.JButton();
        jpDeviceBinding = new javax.swing.JPanel();
        jlbDeviceCode = new javax.swing.JLabel();
        jlbChannelName = new javax.swing.JLabel();
        jlbDeviceBindingManipulate = new javax.swing.JLabel();
        jcbDeviceBindingManipulate = new javax.swing.JComboBox<>();
        jtfDeviceCode = new javax.swing.JTextField();
        jtfChannelName = new javax.swing.JTextField();
        jbDeviceBinding = new javax.swing.JButton();
        jpData = new javax.swing.JPanel();
        jlbDataManipulate = new javax.swing.JLabel();
        jcbDataManipulate = new javax.swing.JComboBox<>();
        jbData = new javax.swing.JButton();
        jpRefundIssue = new javax.swing.JPanel();
        jbRefundReport = new javax.swing.JButton();
        jbCreditReport = new javax.swing.JButton();
        jpRefund = new javax.swing.JPanel();
        jLabel2 = new javax.swing.JLabel();
        jcbRefundIssueManipulate = new javax.swing.JComboBox<>();
        jLabel3 = new javax.swing.JLabel();
        jtfRefundIssueDeviceCode = new javax.swing.JTextField();
        jbRefundIssue = new javax.swing.JButton();
        jSeparator1 = new javax.swing.JSeparator();
        jlbRefundCode = new javax.swing.JLabel();
        jtfRefundCode = new javax.swing.JTextField();
        jcbRefundAgree = new javax.swing.JCheckBox();
        jlbRefundAgree = new javax.swing.JLabel();
        jbRefundAgreeExecute = new javax.swing.JButton();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("房客 App API 測試工具");

        jtaStatus.setColumns(20);
        jtaStatus.setRows(5);
        jScrollPane1.setViewportView(jtaStatus);

        jbClearTextArea.setText("清除");
        jbClearTextArea.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jbClearTextAreaActionPerformed(evt);
            }
        });

        jLabel1.setText("帳號:");

        jtfAccount.setText("dae.benwch@gmail.com");

        jlbHost.setText("主機:");

        jcbHost.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "clhlabs.dae.tw", "clh25.dev.tw", "clh25.dae.tw" }));

        jlbProtocol.setText("協定:");

        jcbProtocol.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "http", "https" }));

        jlbPath.setText("API 路徑:");

        jtfPath.setText("/ws/app.php");

        javax.swing.GroupLayout jpShareLayout = new javax.swing.GroupLayout(jpShare);
        jpShare.setLayout(jpShareLayout);
        jpShareLayout.setHorizontalGroup(
            jpShareLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jpShareLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jpShareLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jpShareLayout.createSequentialGroup()
                        .addComponent(jlbProtocol)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jcbProtocol, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(18, 18, 18)
                        .addComponent(jlbHost)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jcbHost, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(jlbPath)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jtfPath, javax.swing.GroupLayout.DEFAULT_SIZE, 143, Short.MAX_VALUE))
                    .addGroup(jpShareLayout.createSequentialGroup()
                        .addComponent(jLabel1)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jtfAccount, javax.swing.GroupLayout.PREFERRED_SIZE, 200, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(0, 0, Short.MAX_VALUE)))
                .addContainerGap())
        );
        jpShareLayout.setVerticalGroup(
            jpShareLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jpShareLayout.createSequentialGroup()
                .addGap(5, 5, 5)
                .addGroup(jpShareLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jlbProtocol)
                    .addComponent(jcbProtocol, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jlbHost)
                    .addComponent(jcbHost, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jlbPath)
                    .addComponent(jtfPath, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jpShareLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel1)
                    .addComponent(jtfAccount, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addContainerGap(111, Short.MAX_VALUE))
        );

        jtpServicePane.addTab("共用參數", jpShare);

        jlbPassword.setText("密碼:");

        jlbUserCode.setText("房東代碼:");

        jlbLangCode.setText("語系:");

        jcbLangCode.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "English", "正體中文" }));

        jlbAppId.setText("APP ID:");

        jtfAppId.setText("com.dae.TenantAppPub");

        jlbAppVersion.setText("App 版本:");

        jtfAppVersion.setText("1.2.6");

        jtfUserCode.setText("2119270014");

        jpfPassword.setText("a13093059");

        jbLogin.setText("登入");
        jbLogin.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jbLoginActionPerformed(evt);
            }
        });

        jbLogout.setText("登出");
        jbLogout.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jbLogoutActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jpLoginLayout = new javax.swing.GroupLayout(jpLogin);
        jpLogin.setLayout(jpLoginLayout);
        jpLoginLayout.setHorizontalGroup(
            jpLoginLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jpLoginLayout.createSequentialGroup()
                .addGroup(jpLoginLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jpLoginLayout.createSequentialGroup()
                        .addGroup(jpLoginLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addGroup(jpLoginLayout.createSequentialGroup()
                                .addContainerGap()
                                .addGroup(jpLoginLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                                    .addGroup(jpLoginLayout.createSequentialGroup()
                                        .addComponent(jlbAppVersion)
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                        .addComponent(jtfAppVersion))
                                    .addGroup(jpLoginLayout.createSequentialGroup()
                                        .addComponent(jlbAppId)
                                        .addGap(18, 18, 18)
                                        .addComponent(jtfAppId, javax.swing.GroupLayout.PREFERRED_SIZE, 199, javax.swing.GroupLayout.PREFERRED_SIZE))))
                            .addGroup(jpLoginLayout.createSequentialGroup()
                                .addGroup(jpLoginLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                    .addComponent(jlbUserCode)
                                    .addGroup(jpLoginLayout.createSequentialGroup()
                                        .addContainerGap()
                                        .addComponent(jlbPassword)))
                                .addGap(12, 12, 12)
                                .addGroup(jpLoginLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                                    .addComponent(jtfUserCode, javax.swing.GroupLayout.DEFAULT_SIZE, 200, Short.MAX_VALUE)
                                    .addComponent(jpfPassword))
                                .addGap(18, 18, 18)
                                .addComponent(jlbLangCode)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(jcbLangCode, javax.swing.GroupLayout.PREFERRED_SIZE, 107, javax.swing.GroupLayout.PREFERRED_SIZE)))
                        .addGap(0, 65, Short.MAX_VALUE))
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jpLoginLayout.createSequentialGroup()
                        .addGap(0, 0, Short.MAX_VALUE)
                        .addComponent(jbLogout)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jbLogin)))
                .addContainerGap())
        );
        jpLoginLayout.setVerticalGroup(
            jpLoginLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jpLoginLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jpLoginLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jlbPassword)
                    .addComponent(jlbLangCode)
                    .addComponent(jcbLangCode, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jpfPassword, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jpLoginLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jlbUserCode)
                    .addComponent(jtfUserCode, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(6, 6, 6)
                .addGroup(jpLoginLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jlbAppId)
                    .addComponent(jtfAppId, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(9, 9, 9)
                .addGroup(jpLoginLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jlbAppVersion)
                    .addComponent(jtfAppVersion, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, 20, Short.MAX_VALUE)
                .addGroup(jpLoginLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jbLogin)
                    .addComponent(jbLogout))
                .addContainerGap())
        );

        jtpServicePane.addTab("登入", jpLogin);

        jlbDeviceCode.setText("電號:");

        jlbChannelName.setText("迴路名稱:");

        jlbDeviceBindingManipulate.setText("操作類型:");

        jcbDeviceBindingManipulate.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "list", "bind", "unbind", "query" }));

        jtfDeviceCode.setText("1301907119005");

        jtfChannelName.setText("A140");

        jbDeviceBinding.setText("執行");
        jbDeviceBinding.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jbDeviceBindingActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jpDeviceBindingLayout = new javax.swing.GroupLayout(jpDeviceBinding);
        jpDeviceBinding.setLayout(jpDeviceBindingLayout);
        jpDeviceBindingLayout.setHorizontalGroup(
            jpDeviceBindingLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jpDeviceBindingLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jpDeviceBindingLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                    .addGroup(jpDeviceBindingLayout.createSequentialGroup()
                        .addComponent(jlbChannelName)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(jtfChannelName))
                    .addGroup(jpDeviceBindingLayout.createSequentialGroup()
                        .addGroup(jpDeviceBindingLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(jlbDeviceBindingManipulate)
                            .addComponent(jlbDeviceCode))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addGroup(jpDeviceBindingLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                            .addComponent(jcbDeviceBindingManipulate, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(jtfDeviceCode, javax.swing.GroupLayout.DEFAULT_SIZE, 160, Short.MAX_VALUE))))
                .addContainerGap(263, Short.MAX_VALUE))
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jpDeviceBindingLayout.createSequentialGroup()
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addComponent(jbDeviceBinding)
                .addContainerGap())
        );
        jpDeviceBindingLayout.setVerticalGroup(
            jpDeviceBindingLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jpDeviceBindingLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jpDeviceBindingLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jlbDeviceBindingManipulate)
                    .addComponent(jcbDeviceBindingManipulate, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jpDeviceBindingLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jtfDeviceCode, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jlbDeviceCode))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jpDeviceBindingLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jtfChannelName, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jlbChannelName))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, 52, Short.MAX_VALUE)
                .addComponent(jbDeviceBinding)
                .addContainerGap())
        );

        jtpServicePane.addTab("表位綁定", jpDeviceBinding);

        jlbDataManipulate.setText("操作類型:");

        jcbDataManipulate.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "consumption", "channels-consumption", "room-consumption" }));

        jbData.setText("執行");
        jbData.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jbDataActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jpDataLayout = new javax.swing.GroupLayout(jpData);
        jpData.setLayout(jpDataLayout);
        jpDataLayout.setHorizontalGroup(
            jpDataLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jpDataLayout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jlbDataManipulate)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jcbDataManipulate, javax.swing.GroupLayout.PREFERRED_SIZE, 170, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap(259, Short.MAX_VALUE))
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jpDataLayout.createSequentialGroup()
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addComponent(jbData)
                .addContainerGap())
        );
        jpDataLayout.setVerticalGroup(
            jpDataLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jpDataLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jpDataLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jlbDataManipulate)
                    .addComponent(jcbDataManipulate, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, 110, Short.MAX_VALUE)
                .addComponent(jbData)
                .addContainerGap())
        );

        jtpServicePane.addTab("即時抄表", jpData);

        jbRefundReport.setText("退款記錄");
        jbRefundReport.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jbRefundReportActionPerformed(evt);
            }
        });

        jbCreditReport.setText("加值記錄");
        jbCreditReport.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jbCreditReportActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jpRefundIssueLayout = new javax.swing.GroupLayout(jpRefundIssue);
        jpRefundIssue.setLayout(jpRefundIssueLayout);
        jpRefundIssueLayout.setHorizontalGroup(
            jpRefundIssueLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jpRefundIssueLayout.createSequentialGroup()
                .addContainerGap(324, Short.MAX_VALUE)
                .addComponent(jbCreditReport)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jbRefundReport)
                .addContainerGap())
        );
        jpRefundIssueLayout.setVerticalGroup(
            jpRefundIssueLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jpRefundIssueLayout.createSequentialGroup()
                .addContainerGap(139, Short.MAX_VALUE)
                .addGroup(jpRefundIssueLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jbRefundReport)
                    .addComponent(jbCreditReport))
                .addContainerGap())
        );

        jtpServicePane.addTab("報表", jpRefundIssue);

        jLabel2.setText("操作類型:");

        jcbRefundIssueManipulate.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "list", "new", "cancel" }));

        jLabel3.setText("電號:");

        jtfRefundIssueDeviceCode.setText("1301907119005");

        jbRefundIssue.setText("發起退款");
        jbRefundIssue.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jbRefundIssueActionPerformed(evt);
            }
        });

        jlbRefundCode.setText("退款代碼:");

        jcbRefundAgree.setText("不同意");
        jcbRefundAgree.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jcbRefundAgreeActionPerformed(evt);
            }
        });

        jlbRefundAgree.setText("同意退款:");

        jbRefundAgreeExecute.setText("執行");
        jbRefundAgreeExecute.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jbRefundAgreeExecuteActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jpRefundLayout = new javax.swing.GroupLayout(jpRefund);
        jpRefund.setLayout(jpRefundLayout);
        jpRefundLayout.setHorizontalGroup(
            jpRefundLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jpRefundLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jpRefundLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jpRefundLayout.createSequentialGroup()
                        .addGap(0, 0, Short.MAX_VALUE)
                        .addComponent(jbRefundIssue))
                    .addComponent(jSeparator1)
                    .addGroup(jpRefundLayout.createSequentialGroup()
                        .addGroup(jpRefundLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(jLabel2)
                            .addComponent(jLabel3))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addGroup(jpRefundLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                            .addComponent(jcbRefundIssueManipulate, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(jtfRefundIssueDeviceCode, javax.swing.GroupLayout.DEFAULT_SIZE, 170, Short.MAX_VALUE)))
                    .addGroup(jpRefundLayout.createSequentialGroup()
                        .addGroup(jpRefundLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(jlbRefundCode)
                            .addComponent(jlbRefundAgree))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addGroup(jpRefundLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addGroup(jpRefundLayout.createSequentialGroup()
                                .addComponent(jcbRefundAgree, javax.swing.GroupLayout.PREFERRED_SIZE, 170, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                .addComponent(jbRefundAgreeExecute))
                            .addComponent(jtfRefundCode))))
                .addContainerGap())
        );
        jpRefundLayout.setVerticalGroup(
            jpRefundLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jpRefundLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jpRefundLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel2)
                    .addComponent(jcbRefundIssueManipulate, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jpRefundLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel3)
                    .addComponent(jtfRefundIssueDeviceCode, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addComponent(jbRefundIssue)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jSeparator1, javax.swing.GroupLayout.PREFERRED_SIZE, 10, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jpRefundLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jlbRefundCode)
                    .addComponent(jtfRefundCode, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jpRefundLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jcbRefundAgree)
                    .addComponent(jlbRefundAgree)
                    .addComponent(jbRefundAgreeExecute))
                .addGap(7, 7, 7))
        );

        jtpServicePane.addTab("退款", jpRefund);

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, layout.createSequentialGroup()
                        .addGap(0, 0, Short.MAX_VALUE)
                        .addComponent(jbClearTextArea))
                    .addComponent(jtpServicePane, javax.swing.GroupLayout.Alignment.TRAILING)
                    .addComponent(jScrollPane1, javax.swing.GroupLayout.Alignment.TRAILING))
                .addContainerGap())
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jtpServicePane)
                .addGap(18, 18, 18)
                .addComponent(jScrollPane1, javax.swing.GroupLayout.PREFERRED_SIZE, 135, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jbClearTextArea)
                .addContainerGap())
        );

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void jbClearTextAreaActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jbClearTextAreaActionPerformed
        jtaStatus.setText("");
    }//GEN-LAST:event_jbClearTextAreaActionPerformed

    private void jbLoginActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jbLoginActionPerformed
//        if (sessionObject.getLong("expired-at") > System.currentTimeMillis()) {
//            return;
//        }

        //<editor-fold desc="登入服務參數">
        HashMap<String, String> map = new HashMap<>();
        map.put("d", "login");
        map.put("username", jtfAccount.getText());
        map.put("password", new String(jpfPassword.getPassword()));
        map.put("user-code", jtfUserCode.getText());
        map.put("app-id", jtfAppId.getText());
        map.put("app-version", jtfAppVersion.getText());
        map.put("lang-code", "English".equals(jcbLangCode.getSelectedItem().toString()) ? "en" : "zh-TW");
        //</editor-fold>

        Optional<HttpResponse<String>> responseOptional = doRequest(map);
        HttpResponse<String> response = responseOptional.get();
        try (JsonReader loginReader = Json.createReader(new StringReader(response.body()))) {
            JsonObject bodyObject = loginReader.readObject();
            jtaStatus.append(String.format("%s bodyObject = %s%n%n", map.get("d"), bodyObject));
            if (!bodyObject.getBoolean("result", true)) {
                jtaStatus.append(String.format("%s body = %s%n", map.get("d"), response.body()));
                return;
            }
            loginDataObject = bodyObject.getJsonObject("data");
            mqttObject = loginDataObject.getJsonObject("mqtt");
            JsonArray channelsArray = loginDataObject.getJsonArray("channels");
            if (!channelsArray.isEmpty()) {
                channelsObject = channelsArray.getJsonObject(0);
                topicPrefix = String.format("%s/%s", mqttObject.getString("topic"), channelsObject.getString("mac-address"));
                keySeed = String.format("%s+%s", mqttObject.getString("topic"), channelsObject.getString("mac-address"));
            }
        } catch (JsonException e) {
            jtaStatus.append(String.format("%s body = %s%n", map.get("d"), response.body()));
        }
    }//GEN-LAST:event_jbLoginActionPerformed

    private void jbLogoutActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jbLogoutActionPerformed
        //<editor-fold desc="登出服務參數">
        HashMap<String, String> map = new HashMap<>();
        map.put("d", "logout");
        map.put("username", jtfAccount.getText());
        //</editor-fold>

        Optional<HttpResponse<String>> responseOptional = doRequest(map);
        HttpResponse<String> response = responseOptional.get();
        try (JsonReader logoutReader = Json.createReader(new StringReader(response.body()))) {
            JsonObject bodyObject = logoutReader.readObject();
            jtaStatus.append(String.format("%s bodyObject = %s%n%n", map.get("d"), bodyObject));
            if (!bodyObject.getBoolean("result", true)) {
                jtaStatus.append(String.format("%s body = %s%n", map.get("d"), response.body()));
            }
        } catch (JsonException e) {
            jtaStatus.append(String.format("%s body = %s%n", map.get("d"), response.body()));
        }
//        resetSessionId(false);
    }//GEN-LAST:event_jbLogoutActionPerformed

    private void jbDataActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jbDataActionPerformed
        //<editor-fold desc="即時抄表服務參數">
        HashMap<String, String> map = new HashMap<>();
        map.put("d", "data");
        map.put("m", jcbDataManipulate.getSelectedItem().toString());
        map.put("username", jtfAccount.getText());
        //</editor-fold>
        
        Optional<HttpResponse<String>> responseOptional = doRequest(map);
        HttpResponse<String> response = responseOptional.get();
        try {
            JSONObject bodyObject = new JSONObject(response.body());
            jtaStatus.append(String.format("%s bodyObject = %s%n%n", map.get("d"), bodyObject));
            if (!bodyObject.optBoolean("result", true)) {
                jtaStatus.append(String.format("%s body = %s%n", map.get("d"), response.body()));
            }
        } catch (JSONException e) {
            jtaStatus.append(String.format("%s body = %s%n", map.get("d"), response.body()));
        }
    }//GEN-LAST:event_jbDataActionPerformed

    private void jbDeviceBindingActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jbDeviceBindingActionPerformed
        //<editor-fold desc="表位綁定服務參數">
        HashMap<String, String> map = new HashMap<>();
        map.put("d", "device-binding");
        map.put("m", jcbDeviceBindingManipulate.getSelectedItem().toString());
        map.put("username", jtfAccount.getText());
        if (map.get("m").equals("query")) {
            map.remove("device-code");
            map.put("channel-name", jtfChannelName.getText());
        }
        
        if (map.get("m").equals("bind") || map.get("m").equals("unbind")) {
            map.remove("channel-name");
            map.put("device-code", jtfDeviceCode.getText());
        }
        //</editor-fold>
        
        Optional<HttpResponse<String>> responseOptional = doRequest(map);
        HttpResponse<String> response = responseOptional.get();
        try {
            JSONObject bodyObject = new JSONObject(response.body());
            jtaStatus.append(String.format("%s bodyObject = %s%n%n", map.get("d"), bodyObject));
            if (!bodyObject.optBoolean("result", true)) {
                jtaStatus.append(String.format("%s body = %s%n", map.get("d"), response.body()));
            }
        } catch (JSONException e) {
            jtaStatus.append(String.format("%s body = %s%n", map.get("d"), response.body()));
        }
    }//GEN-LAST:event_jbDeviceBindingActionPerformed

    private void jbRefundReportActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jbRefundReportActionPerformed
        //<editor-fold desc="退款記錄服務參數">
        HashMap<String, String> map = new HashMap<>();
        map.put("d", "refund-report");
        map.put("username", jtfAccount.getText());
        //</editor-fold>
        
        Optional<HttpResponse<String>> responseOptional = doRequest(map);
        HttpResponse<String> response = responseOptional.get();
        try {
            JSONObject bodyObject = new JSONObject(response.body());
            jtaStatus.append(String.format("%s bodyObject = %s%n%n", map.get("d"), bodyObject));
            if (!bodyObject.optBoolean("result", true)) {
                jtaStatus.append(String.format("%s body = %s%n", map.get("d"), response.body()));
            }
        } catch (JSONException e) {
            jtaStatus.append(String.format("%s body = %s%n", map.get("d"), response.body()));
        }
    }//GEN-LAST:event_jbRefundReportActionPerformed

    private void jbCreditReportActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jbCreditReportActionPerformed
        //<editor-fold desc="加值記錄服務參數">
        HashMap<String, String> map = new HashMap<>();
        map.put("d", "credit-report");
        map.put("username", jtfAccount.getText());
        //</editor-fold>
        
        Optional<HttpResponse<String>> responseOptional = doRequest(map);
        HttpResponse<String> response = responseOptional.get();
        try {
            JSONObject bodyObject = new JSONObject(response.body());
            jtaStatus.append(String.format("%s bodyObject = %s%n%n", map.get("d"), bodyObject));
            if (!bodyObject.optBoolean("result", true)) {
                jtaStatus.append(String.format("%s body = %s%n", map.get("d"), response.body()));
            }
        } catch (JSONException e) {
            jtaStatus.append(String.format("%s body = %s%n", map.get("d"), response.body()));
        }
    }//GEN-LAST:event_jbCreditReportActionPerformed

    private void jbRefundIssueActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jbRefundIssueActionPerformed
        //<editor-fold desc="發起退款服務參數">
        HashMap<String, String> map = new HashMap<>();
        map.put("d", "refund-issue");
        map.put("m", jcbRefundIssueManipulate.getSelectedItem().toString());
        map.put("username", jtfAccount.getText());
        map.put("device-code", jtfRefundIssueDeviceCode.getText());
        //</editor-fold>
        
        Optional<HttpResponse<String>> responseOptional = doRequest(map);
        HttpResponse<String> response = responseOptional.get();
        try {
            JSONObject bodyObject = new JSONObject(response.body());
            jtaStatus.append(String.format("%s bodyObject = %s%n%n", map.get("d"), bodyObject));
            if (!bodyObject.optBoolean("result", true)) {
                jtaStatus.append(String.format("%s body = %s%n", map.get("d"), response.body()));
            }
        } catch (JSONException e) {
            jtaStatus.append(String.format("%s body = %s%n", map.get("d"), response.body()));
        }
        
    }//GEN-LAST:event_jbRefundIssueActionPerformed

    private void jcbRefundAgreeActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jcbRefundAgreeActionPerformed
        jcbRefundAgree.setText(jcbRefundAgree.isSelected() ? "同意" : "不同意");
    }//GEN-LAST:event_jcbRefundAgreeActionPerformed

    private void jbRefundAgreeExecuteActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jbRefundAgreeExecuteActionPerformed
        //<editor-fold desc="室友同意退款服務參數">
        HashMap<String, String> map = new HashMap<>();
        map.put("d", "refund-agree");
        map.put("username", jtfAccount.getText());
        map.put("refund-code", jtfRefundCode.getText());
        map.put("agree", jcbRefundAgree.isSelected() ? "1" : "0");
        //</editor-fold>
        
        Optional<HttpResponse<String>> responseOptional = doRequest(map);
        HttpResponse<String> response = responseOptional.get();
        try {
            JSONObject bodyObject = new JSONObject(response.body());
            jtaStatus.append(String.format("%s bodyObject = %s%n%n", map.get("d"), bodyObject));
            if (!bodyObject.optBoolean("result", true)) {
                jtaStatus.append(String.format("%s body = %s%n", map.get("d"), response.body()));
            }
        } catch (JSONException e) {
            jtaStatus.append(String.format("%s body = %s%n", map.get("d"), response.body()));
        }
    }//GEN-LAST:event_jbRefundAgreeExecuteActionPerformed

    /**
     * @param args the command line arguments
     */
    public static void main(String args[]) {
        /* Set the Nimbus look and feel */
        //<editor-fold defaultstate="collapsed" desc=" Look and feel setting code (optional) ">
        /* If Nimbus (introduced in Java SE 6) is not available, stay with the default look and feel.
         * For details see http://download.oracle.com/javase/tutorial/uiswing/lookandfeel/plaf.html 
         */
        try {
            String lookAndFeelClassName = Arrays.stream(UIManager.getInstalledLookAndFeels())
                    .filter(info -> "Nimbus".equals(info.getName())).findFirst()
                    .map(UIManager.LookAndFeelInfo::getClassName)
                    .orElse(UIManager.getCrossPlatformLookAndFeelClassName());
            UIManager.setLookAndFeel(lookAndFeelClassName);
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException | javax.swing.UnsupportedLookAndFeelException ex) {
            java.util.logging.Logger.getLogger(MainFrame.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        }
        //</editor-fold>
        
        //</editor-fold>

        /* Create and display the form */
        java.awt.EventQueue.invokeLater(() -> {
            new MainFrame().setVisible(true);
        });
    }

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JLabel jLabel1;
    private javax.swing.JLabel jLabel2;
    private javax.swing.JLabel jLabel3;
    private javax.swing.JScrollPane jScrollPane1;
    private javax.swing.JSeparator jSeparator1;
    private javax.swing.JButton jbClearTextArea;
    private javax.swing.JButton jbCreditReport;
    private javax.swing.JButton jbData;
    private javax.swing.JButton jbDeviceBinding;
    private javax.swing.JButton jbLogin;
    private javax.swing.JButton jbLogout;
    private javax.swing.JButton jbRefundAgreeExecute;
    private javax.swing.JButton jbRefundIssue;
    private javax.swing.JButton jbRefundReport;
    private javax.swing.JComboBox<String> jcbDataManipulate;
    private javax.swing.JComboBox<String> jcbDeviceBindingManipulate;
    private javax.swing.JComboBox<String> jcbHost;
    private javax.swing.JComboBox<String> jcbLangCode;
    private javax.swing.JComboBox<String> jcbProtocol;
    private javax.swing.JCheckBox jcbRefundAgree;
    private javax.swing.JComboBox<String> jcbRefundIssueManipulate;
    private javax.swing.JLabel jlbAppId;
    private javax.swing.JLabel jlbAppVersion;
    private javax.swing.JLabel jlbChannelName;
    private javax.swing.JLabel jlbDataManipulate;
    private javax.swing.JLabel jlbDeviceBindingManipulate;
    private javax.swing.JLabel jlbDeviceCode;
    private javax.swing.JLabel jlbHost;
    private javax.swing.JLabel jlbLangCode;
    private javax.swing.JLabel jlbPassword;
    private javax.swing.JLabel jlbPath;
    private javax.swing.JLabel jlbProtocol;
    private javax.swing.JLabel jlbRefundAgree;
    private javax.swing.JLabel jlbRefundCode;
    private javax.swing.JLabel jlbUserCode;
    private javax.swing.JPanel jpData;
    private javax.swing.JPanel jpDeviceBinding;
    private javax.swing.JPanel jpLogin;
    private javax.swing.JPanel jpRefund;
    private javax.swing.JPanel jpRefundIssue;
    private javax.swing.JPanel jpShare;
    private javax.swing.JPasswordField jpfPassword;
    private javax.swing.JTextArea jtaStatus;
    private javax.swing.JTextField jtfAccount;
    private javax.swing.JTextField jtfAppId;
    private javax.swing.JTextField jtfAppVersion;
    private javax.swing.JTextField jtfChannelName;
    private javax.swing.JTextField jtfDeviceCode;
    private javax.swing.JTextField jtfPath;
    private javax.swing.JTextField jtfRefundCode;
    private javax.swing.JTextField jtfRefundIssueDeviceCode;
    private javax.swing.JTextField jtfUserCode;
    private javax.swing.JTabbedPane jtpServicePane;
    // End of variables declaration//GEN-END:variables
}
