import {
  _decorator,
  Button,
  Color,
  Component,
  EditBox,
  Graphics,
  Label,
  Node,
  UITransform,
} from "cc";
import { GameClient } from "./client.js";
import { CocosWebSocketTransport } from "./cocos-websocket-transport.js";
import { RpcRoute } from "../generated/rpc_routes.js";
import { SCLogin } from "../generated/S2Cs.js";
import { encodeSdkLogin } from "./sdk-test-panel-protocol.js";

const { ccclass, property } = _decorator;

@ccclass("XsNetSdkTestPanel")
export class XsNetSdkTestPanel extends Component {
  @property({ tooltip: "WebSocket endpoint" })
  public defaultUrl = "ws://127.0.0.1:8083/game";

  @property({ tooltip: "JWT used by the game gateway" })
  public defaultJwt = "";

  @property({ tooltip: "Initial player id" })
  public defaultPlayerId = "1001";

  @property({ tooltip: "Initial player name" })
  public defaultPlayerName = "Alice";

  @property({ tooltip: "Initial session id" })
  public defaultSessionId = "cocos-test";

  private urlInput?: EditBox;
  private jwtInput?: EditBox;
  private playerIdInput?: EditBox;
  private playerNameInput?: EditBox;
  private sessionIdInput?: EditBox;
  private statusLabel?: Label;
  private logLabel?: Label;
  private client?: GameClient;
  private closeOff?: () => void;
  private readonly logs: string[] = [];

  protected onLoad(): void {
    this.createPanel();
    this.setStatus("未连接");
    this.appendLog("请输入地址和 JWT，然后点击连接。");
  }

  protected onDestroy(): void {
    this.closeClient();
  }

  private createPanel(): void {
    const panel = new Node("XSNetSdkTestPanel");
    panel.setParent(this.node);
    panel.setPosition(0, 0, 0);
    panel.addComponent(UITransform).setContentSize(780, 500);

    const background = panel.addComponent(Graphics);
    background.fillColor = new Color(22, 28, 40, 235);
    background.roundRect(-390, -250, 780, 500, 14);
    background.fill();

    this.createLabel(panel, "Title", "XS-NET Cocos SDK 连通性测试", 0, 218, 700, 34, 24);
    this.urlInput = this.createInput(panel, "Url", "WebSocket 地址", this.defaultUrl, 70, 165);
    this.jwtInput = this.createInput(panel, "Jwt", "JWT", this.defaultJwt, 70, 115);
    this.playerIdInput = this.createInput(panel, "PlayerId", "玩家 ID", this.defaultPlayerId, 70, 65);
    this.playerNameInput = this.createInput(panel, "PlayerName", "玩家名", this.defaultPlayerName, 70, 15);
    this.sessionIdInput = this.createInput(panel, "SessionId", "会话 ID", this.defaultSessionId, 70, -35);

    this.createButton(panel, "Connect", "连接", -190, -100, () => void this.connect());
    this.createButton(panel, "SendLogin", "发送登录", 0, -100, () => this.sendLogin());
    this.createButton(panel, "Close", "断开", 190, -100, () => this.disconnect());
    this.statusLabel = this.createLabel(panel, "Status", "状态：未连接", -350, -145, 700, 28, 18);
    this.logLabel = this.createLabel(panel, "Log", "", -350, -200, 700, 82, 16);
  }

  private createLabel(
    parent: Node,
    name: string,
    value: string,
    x: number,
    y: number,
    width: number,
    height: number,
    fontSize: number,
  ): Label {
    const node = new Node(name);
    node.setParent(parent);
    node.setPosition(x, y, 0);
    node.addComponent(UITransform).setContentSize(width, height);
    const label = node.addComponent(Label);
    label.string = value;
    label.fontSize = fontSize;
    label.lineHeight = fontSize + 6;
    label.color = new Color(232, 238, 246, 255);
    label.horizontalAlign = Label.HorizontalAlign.LEFT;
    label.verticalAlign = Label.VerticalAlign.CENTER;
    return label;
  }

  private createInput(parent: Node, name: string, placeholder: string, value: string, x: number, y: number): EditBox {
    const node = new Node(name);
    node.setParent(parent);
    node.setPosition(x, y, 0);
    node.addComponent(UITransform).setContentSize(570, 36);
    const input = node.addComponent(EditBox);
    input.placeholder = placeholder;
    input.string = value;
    return input;
  }

  private createButton(parent: Node, name: string, text: string, x: number, y: number, handler: () => void): void {
    const node = new Node(name);
    node.setParent(parent);
    node.setPosition(x, y, 0);
    node.addComponent(UITransform).setContentSize(150, 38);
    node.addComponent(Button);
    this.createLabel(node, "Text", text, 0, 0, 150, 38, 18);
    node.on(Node.EventType.TOUCH_END, handler, this);
  }

  private async connect(): Promise<void> {
    const url = this.urlInput?.string.trim() ?? "";
    const jwt = this.jwtInput?.string ?? "";
    if (!url) {
      this.appendLog("连接失败：WebSocket 地址为空。");
      return;
    }

    this.closeClient();
    const transport = new CocosWebSocketTransport(url);
    const client = new GameClient(transport);
    this.client = client;
    client.dispatcher.registerDecoded(RpcRoute.RPC_SC_LOGIN, SCLogin.decode, (response) => {
      this.appendLog(`收到登录响应：result=${response.result ?? "undefined"}, playerId=${response.playerId ?? ""}`);
    });
    this.closeOff = transport.onClose((error) => {
      this.setStatus("已断开");
      this.appendLog(`连接关闭${error ? `：${error.message}` : ""}`);
    });

    this.setStatus("连接中...");
    this.appendLog(`正在连接 ${url}`);
    try {
      await client.connect(jwt);
      this.setStatus("已连接");
      this.appendLog("WebSocket 已连接，JWT 已发送。");
    } catch (error) {
      this.setStatus("连接失败");
      this.appendLog(`连接失败：${this.errorMessage(error)}`);
      this.closeClient();
    }
  }

  private sendLogin(): void {
    if (!this.client?.connected) {
      this.appendLog("发送失败：当前未连接。");
      return;
    }

    const payload = encodeSdkLogin({
      playerId: this.playerIdInput?.string.trim() ?? "",
      playerName: this.playerNameInput?.string.trim() ?? "",
      sessionId: this.sessionIdInput?.string.trim() ?? "",
    });
    this.client.send(RpcRoute.RPC_CS_LOGIN, payload);
    this.appendLog("已发送 CSLogin。");
  }

  private disconnect(): void {
    this.closeClient();
    this.setStatus("已断开");
    this.appendLog("连接已断开。");
  }

  private closeClient(): void {
    this.closeOff?.();
    this.closeOff = undefined;
    this.client?.close();
    this.client = undefined;
  }

  private setStatus(value: string): void {
    if (this.statusLabel) this.statusLabel.string = `状态：${value}`;
  }

  private appendLog(value: string): void {
    this.logs.push(value);
    while (this.logs.length > 5) this.logs.shift();
    if (this.logLabel) this.logLabel.string = this.logs.join("\n");
  }

  private errorMessage(error: unknown): string {
    return error instanceof Error ? error.message : String(error);
  }
}
