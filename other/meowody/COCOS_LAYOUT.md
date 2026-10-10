# CocosStudio 自适应布局

ObjectData 支持以下导出属性：

| 属性 | 行为 |
| --- | --- |
| HorizontalEdge | `None`、`LeftEdge`、`RightEdge`、`BothEdge` |
| VerticalEdge | `None`、`BottomEdge`、`TopEdge`、`BothEdge` |
| StretchWidthEnable / StretchHeightEnable | 对应方向随父容器尺寸调整 |

`BothEdge` + Stretch 保持两端边距，类似 RectTransform 的双端锚点。
`BothEdge` 未开启 Stretch 时保持尺寸，锚点位置保持在父容器中的原始比例。
单边或 `None` + Stretch 按原始父子尺寸比例改变大小，符合 CocosStudio 语义。
单边未开启 Stretch 时保持尺寸和对应边距。

支持 `LeftMargin`、`RightMargin`、`BottomMargin`、`TopMargin`；未导出时从
初始位置、尺寸与父容器尺寸推导，显式的零边距有效。尺寸最小为零。
`AnchorPoint` 决定旋转原点，拉伸时同步更新。布局使用未缩放的本地尺寸。

例如填充父容器并保持四周边距：

```json
{
  "HorizontalEdge": "BothEdge",
  "VerticalEdge": "BothEdge",
  "StretchWidthEnable": true,
  "StretchHeightEnable": true,
  "LeftMargin": 20,
  "RightMargin": 20,
  "BottomMargin": 10,
  "TopMargin": 10
}
```

创建后修改根容器尺寸：

```java
Group root = editor.createGroup();
root.setSize(viewport.getWorldWidth(), viewport.getWorldHeight());
stage.addActor(root);
// 正常调用 stage.act(delta)，下一次 act 会从父到子更新布局。
// 如需立即读取新坐标，可调用 root.act(0)。
```

根容器尺寸需由调用方设置。约束挂在节点的持久 Action 上，`clearActions()`
会移除该节点的自适应布局；父尺寸未变化时不会覆盖节点的位置动画。
未配置布局的轴（Edge 缺失、空字符串或 `None`，且 Stretch 关闭）默认保持
相对父容器中心的偏移，尺寸不变：父宽增加 100 时，节点 X 增加 50。
两个轴独立处理；要固定左下角位置，可明确设置 `LeftEdge` / `BottomEdge`。
本功能不包含百分比布局的其他导出属性。

使用 `App.ui()` 打开的 `UIKind.PAGE` 页面会自动填充 viewport 的世界尺寸，
打开和窗口 resize 时均更新 `BaseUI` 及 JSON 根节点；子节点在下一次 act 中
重新布局。WINDOW / MODAL 保持现有缩放居中行为。
默认 ExtendViewport 根据宽高比扩展世界尺寸；只同比例放大窗口时世界尺寸
不变，节点的本地坐标也不变。

回归检查：`gradlew.bat :meowody:layoutCheck`，不需要图形环境。

## TLoadingBar

按 UIProgressFillImage 的两端截取方式绘制：实际填充长度为控件长度乘以
进度；小于图片原始长度时，各截取一半长度的左/右端（垂直为下/上端）
拼接，横向保持图片原始高度、纵向保持原始宽度。大于等于原始长度时，
用 NinePatch 绘制：自动取 Sprite 中心的一像素作为伸缩区域，保留四周
边缘尺寸，无需额外配置切片数据。偶数尺寸图片也保留一像素伸缩区域。
修正了参考代码垂直分支以宽度
代替原始高度的笔误。

```java
TLoadingBar bar = ui.require("Progress", TLoadingBar.class);
bar.setValue(60);          // 0–100，兼容原接口
bar.setFillAmount(0.625f); // 0–1，支持平滑进度
bar.setHorizontal(false); // true 水平（默认），false 垂直
```

JSON 保留 `ProgressInfo`，可添加 `"Horizontal": false` 开启垂直绘制。
进度更新不改变 Actor 的布局尺寸，不影响 RectTransform 风格适配。
保留源图集 UV，支持翻转的 TextureRegion，以及 Actor 的原点、旋转、
缩放、颜色和父级透明度。未增加材质、裁剪器或绘制时分配。

回归检查：`gradlew.bat :meowody:loadingBarCheck`。
