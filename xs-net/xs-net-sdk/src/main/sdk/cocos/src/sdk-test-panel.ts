import {
  _decorator,
  Button,
  Color,
  Component,
  EditBox,
  Graphics,
  Label,
  Mask,
  Node,
  ScrollView,
  UITransform,
} from "cc";
import { GameClient } from "./client.js";
import { CocosWebSocketTransport } from "./cocos-websocket-transport.js";
import { RPC_DEFINITIONS, type RpcDefinition } from "./sdk-test-panel-protocol.js";

const { ccclass, property } = _decorator;

const PANEL_WIDTH = 1040;
const PANEL_HEIGHT = 700;
const ROW_WIDTH = 960;
const FIELD_COLUMNS = 5;
const FIELD_WIDTH = 165;
const FIELD_ROW_HEIGHT = 62;
const ROW_GAP = 10;

@ccclass("XsNetSdkTestPanel")
export class XsNetSdkTestPanel extends Component {
  @property({ tooltip: "WebSocket endpoint" })
  public defaultUrl = "ws://127.0.0.1:8083/game";

  @property({ tooltip: "JWT used by the game gateway" })
  public defaultJwt = "";

  private urlInput?: EditBox;
  private jwtInput?: EditBox;
  private statusLabel?: Label;
  private logLabel?: Label;
  private client?: GameClient;
  private closeOff?: () => void;
  private readonly logs: string[] = [];

  protected onLoad(): void {
    this.createPanel();
    this.setStatus("未连接");
    this.appendLog("填写地址和 JWT 后点击连接，再按 RPC 行填写参数并发送。");
  }

  protected onDestroy(): void {
    this.closeClient();
  }

  private createPanel(): void {
    const panel = new Node("XSNetSdkTestPanel");
    panel.setParent(this.node);
    panel.setPosition(0, 0, 0);
    panel.addComponent(UITransform).setContentSize(PANEL_WIDTH, PANEL_HEIGHT);

    const background = panel.addComponent(Graphics);
    background.fillColor = new Color(22, 28, 40, 248);
    background.roundRect(-PANEL_WIDTH / 2, -PANEL_HEIGHT / 2, PANEL_WIDTH, PANEL_HEIGHT, 14);
    background.fill();

    const title = this.createLabel(panel, "Title", "XS-NET Cocos SDK RPC 测试", 0, 314, 900, 40, 24);
    title.horizontalAlign = Label.HorizontalAlign.CENTER;

    this.createLabel(panel, "UrlLabel", "WebSocket", -450, 262, 120, 34, 15);
    this.urlInput = this.createInput(panel, "Url", "WebSocket 地址", this.defaultUrl, 55, 262, 800, 38, 15, undefined, EditBox.InputMode.SINGLE_LINE);
    this.createLabel(panel, "JwtLabel", "JWT", -450, 214, 120, 34, 15);
    this.jwtInput = this.createInput(panel, "Jwt", "JWT", this.defaultJwt, 55, 214, 800, 38, 15, 2048, EditBox.InputMode.SINGLE_LINE);

    this.createButton(panel, "Connect", "连接", -180, 164, () => void this.connect());
    this.createButton(panel, "Close", "断开", 180, 164, () => this.disconnect());
    this.statusLabel = this.createLabel(panel, "Status", "状态：未连接", -455, 124, 910, 28, 15);

    this.createLabel(panel, "RpcHeader", "RPC", -430, 96, 80, 26, 14);
    this.createLabel(panel, "ProtoHeader", "Proto 名称", -220, 96, 300, 26, 14);
    this.createLabel(panel, "FieldHeader", "参数（序号：字段名）", -45, 96, 700, 26, 14);
    this.createLabel(panel, "ActionHeader", "操作", 430, 96, 80, 26, 14);

    this.createRpcList(panel);
    this.logLabel = this.createLabel(panel, "Log", "", -455, -320, 910, 38, 13);
  }

  private createRpcList(panel: Node): void {
    const scrollNode = new Node("RpcScrollView");
    scrollNode.setParent(panel);
    scrollNode.setPosition(0, -105, 0);
    scrollNode.addComponent(UITransform).setContentSize(980, 390);
    scrollNode.addComponent(Mask);

    const scrollView = scrollNode.addComponent(ScrollView);
    scrollView.horizontal = false;
    scrollView.vertical = true;

    const rowHeights = RPC_DEFINITIONS.map((definition) => this.rpcRowHeight(definition));
    const totalHeight = rowHeights.reduce((sum, height) => sum + height, 0) + ROW_GAP * Math.max(0, rowHeights.length - 1);
    const content = new Node("RpcContent");
    content.setParent(scrollNode);
    content.addComponent(UITransform).setContentSize(ROW_WIDTH, totalHeight);
    scrollView.content = content;

    let y = totalHeight / 2;
    RPC_DEFINITIONS.forEach((definition, index) => {
      const height = rowHeights[index];
      const row = this.createRpcRow(content, definition, height);
      y -= height / 2;
      row.setPosition(0, y, 0);
      y -= height / 2 + ROW_GAP;
    });
  }

  private rpcRowHeight(definition: RpcDefinition): number {
    const fieldRows = Math.max(1, Math.ceil(definition.fields.length / FIELD_COLUMNS));
    return 50 + fieldRows * FIELD_ROW_HEIGHT + 10;
  }

  private createRpcRow(parent: Node, definition: RpcDefinition, rowHeight: number): Node {
    const row = new Node(`Rpc${definition.rpcNum}`);
    row.setParent(parent);
    row.addComponent(UITransform).setContentSize(ROW_WIDTH, rowHeight);

    const background = row.addComponent(Graphics);
    background.fillColor = new Color(31, 39, 54, 235);
    background.roundRect(-ROW_WIDTH / 2, -rowHeight / 2, ROW_WIDTH, rowHeight, 8);
    background.fill();

    const headerY = rowHeight / 2 - 25;
    this.createLabel(row, "Rpc", String(definition.rpcNum), -430, headerY, 80, 30, 14);
    this.createLabel(row, "Proto", definition.protoName, -220, headerY, 300, 30, 14);

    const inputs = new Map<string, EditBox>();
    const fieldRows = Math.max(1, Math.ceil(definition.fields.length / FIELD_COLUMNS));
    definition.fields.forEach((field, index) => {
      const column = index % FIELD_COLUMNS;
      const fieldRow = Math.floor(index / FIELD_COLUMNS);
      const x = -400 + column * FIELD_WIDTH;
      const y = rowHeight / 2 - 62 - fieldRow * FIELD_ROW_HEIGHT;
      this.createLabel(row, `Field${field.index}`, `${field.index}: ${field.name}`, x, y + 18, 154, 20, 11);
      const input = this.createInput(
        row,
        `Input${field.index}`,
        field.repeated ? `${field.type}[]` : field.type,
        "",
        x,
        y - 14,
        150,
        30,
        13,
      );
      inputs.set(field.name, input);
    });

    this.createButton(row, "Send", "发送", 430, headerY, () => this.sendRpc(definition, inputs));
    return row;
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
    label.lineHeight = fontSize + 5;
    label.color = new Color(232, 238, 246, 255);
    label.horizontalAlign = Label.HorizontalAlign.LEFT;
    label.verticalAlign = Label.VerticalAlign.CENTER;
    label.overflow = Label.Overflow.CLAMP;
    return label;
  }

  private createInput(
    parent: Node,
    name: string,
    placeholder: string,
    value: string,
    x: number,
    y: number,
    width: number,
    height: number,
    fontSize: number,
    maxLength?: number,
    inputMode?: EditBox.InputMode,
  ): EditBox {
    const node = new Node(name);
    node.active = false;
    node.setParent(parent);
    node.setPosition(x, y, 0);
    node.addComponent(UITransform).setContentSize(width, height);

    const background = node.addComponent(Graphics);
    background.fillColor = new Color(12, 17, 26, 255);
    background.roundRect(-width / 2, -height / 2, width, height, 5);
    background.fill();

    const input = node.addComponent(EditBox);
    if (maxLength !== undefined) input.maxLength = maxLength;
    input.inputMode = inputMode ?? EditBox.InputMode.SINGLE_LINE;
    input.placeholder = placeholder;
    input.string = value;
    node.active = true;
    this.configureEditBox(input, width, height, fontSize);
    this.scheduleOnce(() => this.configureEditBox(input, width, height, fontSize), 0);
    return input;
  }

  private configureEditBox(input: EditBox, width: number, height: number, fontSize: number): void {
    for (const label of [input.textLabel, input.placeholderLabel]) {
      if (!label) continue;
      label.fontSize = fontSize;
      label.lineHeight = fontSize + 4;
      label.color = new Color(220, 228, 238, 255);
      label.horizontalAlign = Label.HorizontalAlign.LEFT;
      label.verticalAlign = Label.VerticalAlign.CENTER;
      label.overflow = Label.Overflow.CLAMP;
      label.node.setPosition(0, 0, 0);
      label.node.getComponent(UITransform)?.setContentSize(Math.max(1, width - 16), Math.max(1, height - 6));
    }
  }

  private createButton(parent: Node, name: string, text: string, x: number, y: number, handler: () => void): void {
    const node = new Node(name);
    node.setParent(parent);
    node.setPosition(x, y, 0);
    const transform = node.addComponent(UITransform);
    transform.setContentSize(name === "Send" ? 80 : 150, 36);

    const background = node.addComponent(Graphics);
    background.fillColor = new Color(54, 86, 125, 255);
    background.roundRect(-transform.width / 2, -18, transform.width, 36, 6);
    background.fill();

    node.addComponent(Button);
    const label = this.createLabel(node, "Text", text, 0, 0, transform.width, 36, name === "Send" ? 13 : 15);
    label.horizontalAlign = Label.HorizontalAlign.CENTER;
    node.on(Node.EventType.TOUCH_END, handler, this);
  }

  private async connect(): Promise<void> {
    let url = this.urlInput?.string.trim() ?? "";
    const jwt = this.jwtInput?.string ?? "";
    if (!url) {
      this.appendLog("连接失败：WebSocket 地址为空。");
      return;
    }

    try {
      const parsed = new URL(url);
      if (parsed.protocol !== "ws:" && parsed.protocol !== "wss:") {
        throw new Error("地址必须使用 ws:// 或 wss://");
      }
      if (parsed.pathname === "" || parsed.pathname === "/") {
        parsed.pathname = "/game";
        url = parsed.toString();
        if (this.urlInput) this.urlInput.string = url;
        this.appendLog(`已补全 WebSocket 路径：${url}`);
      }
    } catch (error) {
      this.appendLog(`连接失败：WebSocket 地址无效（${this.errorMessage(error)}）`);
      return;
    }

    this.closeClient();
    const transport = new CocosWebSocketTransport(url);
    const client = new GameClient(transport);
    this.client = client;
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

  private sendRpc(definition: RpcDefinition, inputs: Map<string, EditBox>): void {
    if (!this.client?.connected) {
      this.appendLog("发送失败：当前未连接。");
      return;
    }

    try {
      const values: Record<string, string> = {};
      for (const field of definition.fields) values[field.name] = inputs.get(field.name)?.string ?? "";
      this.client.send(definition.rpcNum, definition.encode(values));
      this.appendLog(`已发送 ${definition.rpcNum} ${definition.protoName}`);
    } catch (error) {
      this.appendLog(`发送失败：${this.errorMessage(error)}`);
    }
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
    while (this.logs.length > 3) this.logs.shift();
    if (this.logLabel) this.logLabel.string = this.logs.join("\n");
  }

  private errorMessage(error: unknown): string {
    return error instanceof Error ? error.message : String(error);
  }
}
