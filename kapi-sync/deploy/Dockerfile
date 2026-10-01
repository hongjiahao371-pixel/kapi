# 运行时镜像：只装一个二进制。
#
# 为什么不直接用仓库根目录那个多阶段 Dockerfile：那个要在 NAS 上拉
# golang:1.26-alpine 然后现场编译。家用 NAS 的外网不一定通、CPU 不一定快、
# 拉一次几百 MB，而我们需要的是"把一个已经测过的二进制放上去"。
#
# `FROM scratch` 不需要任何拉取，所以整个部署过程零网络依赖，也就不存在
# "装不上是因为 NAS 连不上 Docker Hub"这种没法排查的失败。
#
# 二进制在 CI/本机用 CGO_ENABLED=0 交叉编译，和 Dockerfile 里的
# `docker run` 路径是同一个产物，不是另一份代码。
FROM scratch

COPY kapi-sync /kapi-sync

VOLUME ["/data"]
EXPOSE 8787

ENV KAPI_DATA=/data \
    KAPI_ADDR=0.0.0.0:8787

# 根身份运行的理由见根目录 Dockerfile 的注释：scratch 镜像里能造成的破坏
# 上限就是"这一个程序对 /data 做了什么"。要收紧就自己加 --user 并先 chown。
ENTRYPOINT ["/kapi-sync"]
