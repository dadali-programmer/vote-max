# Vote Max Service – 投注额存储与高额列表服务

## 一、 项目简介

一个简单的 HTTP 服务，为不同客户提供投注额存储和查询功能。  
每个客户先获取一个 10 分钟有效期的会话 Key，然后可以使用该 Key 为某个投注选项提交投注额。  
服务会记住每个客户对每个投注选项的最高投注额，并支持返回某个投注选项的前 20 条最高投注（每个客户只出现一次，取最大值）。

整个项目不依赖任何第三方框架，只使用 JDK 自带的 `com.sun.net.httpserver.HttpServer`。  
也没有数据库或文件持久化，所有数据都存在内存中，服务重启即清空。

## 二、 如何运行

### 环境要求
- JDK 8 或更高版本（代码已适配 JDK 8）

### 编译
使用powershell cmd命令行下 cd 进入vote-max项目根目录,执行一下命令：
```
javac -encoding UTF-8 -d target\classes src\main\java\com\vote\max\VoteMaxServer.java

```
### 打包
```
jar cfe vote-max-server.jar com.vote.max.VoteMaxServer -C target\classes com
```

### 运行
```
java -jar vote-max-server.jar
```

## 三、 如何验证
保留运行的cmd是程序处于运行状态，重新开一个powershell cmd命令行 cd 进入vote-max项目根目录
### 创建一个会话
比如客户ID为:1234，cmd执行：
```
curl http://localhost:8001/1234/session
```
cmd输出的值为会话唯一key
### 开始投注
假设投注三次，投注提交的ID:888,则将以下命令三次请求中的sessionKey的值改成上一步cmd输出的会话唯一key
投注第一次，cmd执行：
```
curl -X POST -d "4500" "http://localhost:8001/888/stake?sessionkey=xxxxx"
```
投注第二次，cmd执行：
```
curl -X POST -d "2000" "http://localhost:8001/888/stake?sessionkey=xxxxx"
```
投注第三次，cmd执行：
```
curl -X POST -d "500" "http://localhost:8001/888/stake?sessionkey=xxxxx"
```

### 测试最高投注
假设投注ID:888,cmd执行：
```
curl http://localhost:8001/888/highstakes
```

### 验证不同客户投注
从 创建一个会话 小节，重新生成会话ID后，再执行 开始投注 小节，获取 测试最高投注 小节

### 验证会话有效期
程序会话有效期默认为10分钟,选择上述执行的某次会话，修改一下sessionKey的值后，执行：
```
curl -X POST -d "6000" "http://localhost:8001/888/stake?sessionkey=xxx"
```
如果从创建会话已超过10分钟，命令会提示：Invalid or expired session