# MTR4 列车编组预设

在 MTR 仪表板打开列车侧线的选车界面。点击车型名称可替换整组草稿，点击车厢图标可追加一节，拖到中栏可插入指定位置。点击「保存并退出」提交编组。

## 资源包配置

在资源包的 `assets/mtr/mtr_custom_resources.json` 顶层添加 `train_formations`。该字段可以和 `vehicles` 等既有内容并存。

```json
{
  "train_formations": [
    {
      "prefix": "s_train",
      "items": [
        {"id": "s_train_cab_1", "whitelisted_cars": "1", "blacklisted_cars": "%1"},
        {"id": "s_train_cab_2", "whitelisted_cars": "-1", "blacklisted_cars": "%1"},
        {"id": "s_train_trailer", "blacklisted_cars": "1,-1"}
      ]
    }
  ]
}
```

- `prefix` 对应车型的变体 ID 前缀。例如 `s_train_cab_1` 属于 `s_train`，`s_train_small_cab_1` 属于 `s_train_small`。
- `items` 是有序规则。每个车位采用第一条匹配规则，`id` 填已加载车辆的完整 ID，可引用其他变体。
- 车号从 `1` 开始，`-1` 指末节，`-2` 指倒数第二节。多项用逗号分隔。`%2` 匹配偶数车位，`%2+1` 匹配奇数车位。
- 黑名单与白名单按 MTR3 车辆部件的优先级比较。具体车号强于周期，两边强度相同即匹配。例子中的 `%1` 黑名单匹配每个车位，首节和末节再由各自的具体白名单放行。
- 同一资源包内重复的 `prefix` 采用第一条有效配置。多个包定义同一前缀时使用优先级较高的配置。重新加载资源包后，下次打开选车界面会使用最新资源。

方速从侧线能够容纳的节数上界开始尝试，采用各车位都有匹配且通过 MTR 容量校验的最长编组。缺少引用车辆或放不下完整编组时保留草稿。没有预设时，同一变体的 `cab_1` 和 `cab_2` 保留在两端，其余空间尽量填入 `trailer`。
