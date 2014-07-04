##基础类解释
----------

###`QueryState` 关于一个query的状态
    Frame #CQL binary protocol is a frame based protocol, 整个query source
    clock
    preparedTracingSession
    ClientState
####ClientState 关于一个connection的状态
    keyspace
    AuthenticatedUser
	isInternal(是否有能力修改system keyspaces）
	remoteAddress
###`DefaultQueryOptions`
    query_consistency
    values (绑定到查询语句中的变量)
    skip_metadata
    SpecificOptions
####`SpecificOptions`
    pageSize
	pagingState
	serial consistency
	timestamp
###`DynamicEndpointSnitch`
包含一个snitch使用，通过各个节点(endpoints)的运行状况(Performace)来动态更新节点已感知的远近程度, 好处是避免把流量导入(route)响应很慢的节点
    >IEndpointSnitch subsnitch

####DynamicEndpointSnitch的使用情况

 1. 参数配置
    UPDATE_INTERVAL_IN_MS
    RESET_INTERVAL_IN_MS
    BADNESS_THRESHOLD
    ```If set greater than zero and read_repair_chance is < 1.0, this will allow 'pinning'(固定) of replicas to hosts in order to increase cache capacity. The badness threshold will control how much worse the pinned host has to be before the dynamic snitch will prefer other replicas over it(切换). This is expressed as a double which represents a percentage. Thus, a value of 0.2 means Cassandra would continue to prefer the static snitch values until the pinned host was 20% worse than the fastest.```
