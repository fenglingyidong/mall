#!/usr/bin/env python3

from concurrent.futures import ThreadPoolExecutor
import pymysql
import time
import threading

#database connection info
config = {
    'user': 'root@test',
    'password': '****',
    'host': 'xxx.xxx.xxx.xxx',
    'port': 2881,
    'database': 'test'
}

#parallel thread and updates in each thread
parallel = 50
batch_num = 2000

#update query
def update_elr():
    update_hot_row = ("update sbtest1 set k=k+1 where id=1")
    cnx = pymysql.connect(**config)
    cursor = cnx.cursor()

    for i in range(0,batch_num):
        cursor.execute(update_hot_row)
    cursor.close()
    cnx.close()

start=time.time()

with ThreadPoolExecutor(max_workers=parallel) as pool:
    for i in range(parallel):
        pool.submit(update_elr)

end = time.time()
elapse_time = round((end-start),2)

print('Parallel Degree:',parallel)
print('Total Updates:',parallel*batch_num)
print('Elapse Time:',elapse_time,'s')
print('TPS on Hot Row:' ,round(parallel*batch_num/elapse_time,2),'/s')
