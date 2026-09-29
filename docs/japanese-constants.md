# 日服详细定数自动同步

本模块读取公开的社区定数，不是 SEGA 官方实时 API。国服 `levelValue`、B50、Rating 计算不受影响。

## 数据源与时效

- 主站：https://otoge-db.net/maimai/data/music-ex.json
- 同仓库备用：https://raw.githubusercontent.com/zvuc/otoge-db/main/maimai/data/music-ex.json
- 来源说明：https://github.com/zvuc/otoge-db
- 上游定数采集任务：https://github.com/zvuc/otoge-db/blob/main/.github/workflows/maimai-update-constants.yml

2026-09-29 核验：上游任务每 4 小时检查一次，产生的修改仍经过 PR 发布流程；这不是更新延迟的保证。客户端只能跟随已发布的数据，不能保证游戏改动后立即获知。

App 启动/回到前台时检查，前台期间每 5 分钟检查，谱面查询页刷新可手动触发。ETag 条件请求减少未变化时的流量，不依赖发布新 APK；不在退出软件后持续轮询。显示“源文件更新时间”与“成功检查时间”，二者不混用。源文件更新时间也不代表每张谱面定数的修改时间。

## 安全与匹配

独立缓存全量日服定数。按 NFC 曲名、标准/DX、难度匹配，不混用国服歌曲 ID 与上游排序 ID。同名冲突、已知物量不同不匹配；没有详细定数时不从显示等级推算。只读日服 `_i` 字段，不读国际服字段或宴会谱。

拒绝空数据、HTML、明显截断的数据、已知旧时间戳或倒退的收录日期。主源不可用时尝试同仓库备用源；均失败则保留旧缓存并明确显示同步失败。请求不发送用户 Cookie、Token 或成绩。备用源可能受大陆网络环境影响，未保证所有地区可达。

目前谱面详情显示匹配到的日服定数；未将日服独有歌曲插入国服曲库，也未增加日服成绩导入、日服 Rating 或其他区域切换功能。

## 验证

离线单元测试覆盖标准/DX/难度分离、未知值、同名冲突、物量变化、ETag 304、备用源、坏数据和旧数据保护。
设置环境变量 `FLUENTMAI_JP_LIVE_FIXTURE` 为手动下载的源 JSON 路径可额外运行全量解析和国服匹配统计；常规测试不访问网络。
