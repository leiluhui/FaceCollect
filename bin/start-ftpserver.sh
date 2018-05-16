#!/bin/bash
################################################################################
## Copyright:   HZGOSUN Tech. Co, BigData
## Filename:    start-consumer.sh
## Description: to start consumer
## Author:      liushanbin
## Created:     2018-01-08
################################################################################
#set -x  ## 用于调试用，不用的时候可以注释掉

#---------------------------------------------------------------------#
#                              定义变量                                #
#---------------------------------------------------------------------#
cd `dirname $0`
BIN_DIR=`pwd`                                          ### bin目录
cd ..
OBJECT_DIR=`pwd`                                       ### 项目根目录
LOG_DIR=${OBJECT_DIR}/logs                             ## log 日记目录
LOG_FILE=${LOG_DIR}/ftpserver.log                      ##  log 日记文件
CONF_DIR=$OBJECT_DIR/conf                              ### 配置文件目录
LIB_DIR=$OBJECT_DIR/lib                                ## Jar 包目录

FTP_JARS=`ls ${OBJECT_DIR} | grep .jar | awk '{print "'${OBJECT_DIR}'/"$0}'|tr "\n" ":"`
LIB_JARS=`ls $LIB_DIR|grep .jar|awk '{print "'$LIB_DIR'/"$0}'|tr "\n" ":"`    ## jar 包位置以及第三方依赖jar包，绝对路径
LIB_JARS=${LIB_JARS}${FTP_JARS}

SERVER_NAME=`sed '/ftpserver.application.name/!d;s/.*=//' conf/cluster-over-ftp.properties | tr -d '\r'` #获取服务名称
SERVER_PORT=`sed '/ftp.port/!d;s/.*=//' conf/cluster-over-ftp.properties | tr -d '\r'` #获取服务端口号

if [ -z "$SERVER_NAME" ]; then
    SERVER_NAME=`hostname`
fi


if [ -n "$SERVER_PORT" ]; then
    SERVER_PORT_COUNT=`netstat -tln | grep $SERVER_PORT | wc -l`
    if [ $SERVER_PORT_COUNT -gt 0 ]; then
        echo "ERROR: The $SERVER_NAME port $SERVER_PORT already used!"
        exit 1
    fi
fi



echo "$SERVER_NAME:$SERVER_PORT is starting ..."
#####################################################################
# 函数名: start_consumer
# 描述: 把consumer 消费组启动起来
# 参数: N/A
# 返回值: N/A
# 其他: N/A
#####################################################################
function start_ftpserver()
{
    if [ ! -d $LOG_DIR ]; then
        mkdir $LOG_DIR;
    fi
    echo "$SERVER_NAME:$SERVER_PORT started ..."
    nohup java -server -Xms2g -Xmx4g  -XX:PermSize=512m -XX:MaxPermSize=512m  -classpath $CONF_DIR:$LIB_JARS com.hzgc.collect.FTP > ${LOG_FILE} 2>&1 &
}

#####################################################################
# 函数名: drop_caches
# 描述: ftp 启动的时候，在每个ftp 服务器起一个清楚缓存的定时任务
# 参数: N/A
# 返回值: N/A
# 其他: N/A
#####################################################################
function drop_caches()
{
    boolDropCaches=$(grep drop_caches /etc/crontab | wc  -l)
    if [ "$boolDropCaches" == "1" ];then
        echo "定时清理缓存已经加到定时任务中。"
    else
        echo "* */1 * * * root sync;echo 3 > /proc/sys/vm/drop_caches" >> /etc/crontab
        service crond restart
    fi
}



#####################################################################
# 函数名: main
# 描述: 脚本主要业务入口
# 参数: N/A
# 返回值: N/A
# 其他: N/A
#####################################################################
function main()
{
    drop_caches
    start_ftpserver
    ##对是否存在守护进程判断
    check_ftp_pid=$(ps -ef | grep start-check-ftpserver.sh |grep -v grep | awk  '{print $2}' | uniq)
    if [ -n "${check_ftp_pid}" ];then
        echo "check_ftpserver is exit,nothing to do " | tee -a $LOG_FILE
    else
        echo "check_ftpserver is not exit, just to start check_ftpserver."   | tee -a $LOG_FILE
        nohup sh ${BIN_DIR}/start-check-ftpserver.sh &
    fi

}

## 脚本主要业务入口
main