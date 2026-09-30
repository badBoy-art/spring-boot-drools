# 容器部署入口

先 `mvn verify`，按根 README 初始化数据库，再向进程环境注入 compose 要求的变量（不要提交含密码的 .env 文件）。

```sh
docker compose -f deploy/compose.yaml build
docker compose -f deploy/compose.yaml up -d
```

示例只启动 Center 和一个 Worker，数据库与 TLS 代理由部署环境提供。容器内数据库地址必须是容器可达地址；不能直接使用宿主机的 127.0.0.1。基础镜像需要按公司标准固定 digest、漏洞扫描和维护。本次验收使用宿主机 jar + 独立 MySQL，没有构建/部署这些镜像。

容器以非 root UID10001 运行。Maven 仓库需要时挂载只读 settings 与可写缓存到 /app/.m2；业务 jar/额外 KIE plugin 需加入应用 classpath 并保持所有节点版本一致。修改 classpath 时可使用项目源码构建扩展镜像，不要运行中手动修改 jar。

多 Worker 须各自唯一 node ID 与客户端可达 owner URL，另配会话路由。生产资源配额、自动扩缩容、监控、备份恢复按 [生产部署](../docs/production.md) 验收。
