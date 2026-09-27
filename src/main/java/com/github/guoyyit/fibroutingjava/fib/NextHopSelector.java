package com.github.guoyyit.fibroutingjava.fib;

import com.github.guoyyit.fibroutingjava.model.NextHop;
import java.util.List;

/**
 * 多路径下一跳选择策略。
 * 同一 (候选集合, flowKey) 必须得到同一结果（确定性、可复现）；
 * 权重决定各下一跳被选比例；禁用/失效下一跳不得参与。
 */
public interface NextHopSelector {

    NextHop select(List<NextHop> candidates, String flowKey);
}
