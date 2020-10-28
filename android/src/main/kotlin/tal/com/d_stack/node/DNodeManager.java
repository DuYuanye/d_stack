package tal.com.d_stack.node;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import tal.com.d_stack.DStack;
import tal.com.d_stack.action.DActionManager;
import tal.com.d_stack.lifecycle.PageLifecycleManager;
import tal.com.d_stack.node.constants.DNodeActionType;
import tal.com.d_stack.node.constants.DNodePageType;
import tal.com.d_stack.observer.DStackActivityManager;
import tal.com.d_stack.utils.DLog;

/**
 * 节点管理
 */
public class DNodeManager {

    private final static AtomicReference<DNodeManager> INSTANCE = new AtomicReference<>();

    //当前节点集合
    List<DNode> nodeList = new ArrayList<>();
    //需要移除的节点在节点集合的索引
    List<Integer> needRemoveNodesIndex = new ArrayList<>();
    //需要移除的节点集合
    List<DNode> needRemoveNodes = new ArrayList<>();
    //当前节点
    DNode currentNode;
    //节点动作类型
    String actionType;

    public static DNodeManager getInstance() {
        for (; ; ) {
            DNodeManager factory = INSTANCE.get();
            if (factory != null) {
                return factory;
            }
            factory = new DNodeManager();
            if (INSTANCE.compareAndSet(null, factory)) {
                return factory;
            }
        }
    }

    /**
     * /创建节点
     *
     * @param target      路由信息
     * @param uniqueId    唯一id
     * @param pageType    页面类型
     * @param actionType  页面动作类型
     * @param params      参数
     * @param fromFlutter 是否来自flutter消息通道
     * @return
     */
    public DNode createNode(String target,
                            String uniqueId,
                            String pageType,
                            String actionType,
                            Map<String, Object> params,
                            boolean fromFlutter) {
        DNode node = new DNode();
        node.setTarget(target);
        node.setUniqueId(uniqueId);
        node.setAction(actionType);
        node.setPageType(pageType);
        node.setParams(params);
        node.setFromFlutter(fromFlutter);
        return node;
    }

    //获取当前节点
    public DNode getCurrentNode() {
        return currentNode;
    }

    //检查节点
    public void checkNode(DNode node) {
        if (node == null) {
            return;
        }
        actionType = node.getAction();
        switch (actionType) {
            case DNodeActionType.DNodeActionTypePush:
            case DNodeActionType.DNodeActionTypePresent:
                //打开新页面
                //入栈管理
                //去重逻辑
                DLog.logD("----------push方法开始----------");
                handlePush(node);
                updateNodes();
                setCurrentNodeContainer();
                DActionManager.push(node);
                PageLifecycleManager.pageAppear(node);
                DLog.logD("----------push方法结束----------");
                break;
            case DNodeActionType.DNodeActionTypePop:
            case DNodeActionType.DNodeActionTypeDissmiss:
                //返回上一个页面
                //出栈管理
                //移除最后一个节点即可
                DLog.logD("----------pop方法开始----------");
                DLog.logD("node出栈，target：" + node.getTarget());
                if (node.isFromFlutter()) {
                    if (currentNode != null) {
                        //此处是flutter侧点击左上角返回键的逻辑
                        //flutter页面触发的pop有可能不带target信息，需要手动添加
                        //所有flutter侧页面关闭删除节点的逻辑都在handleNeedRemoveNode实现
                        node.setTarget(currentNode.getTarget());
                        node.setPageType(currentNode.getPageType());
                    }
                    DActionManager.pop(node);
                } else {
                    //此处是关闭native页面清除节点逻辑
                    handleNeedRemoveNativeNode(node);
                }
                DLog.logD("----------pop方法结束----------");
                break;
            case DNodeActionType.DNodeActionTypePopTo:
                //返回指定页面
                DLog.logD("----------popTo方法开始----------");
                needRemoveNodes.clear();
                needRemoveNodesIndex.clear();
                needRemoveNodes = needRemoveNodes(node);
                DNode popToNode = getCurrentNode();
                deleteNodes();
                updateNodes();
                DActionManager.popTo(node, needRemoveNodes);
                PageLifecycleManager.pageDisappear(popToNode);
                DLog.logD("----------popTo方法结束----------");
                break;
            case DNodeActionType.DNodeActionTypePopToRoot:
                //返回最根节点
                DLog.logD("----------popToRoot方法开始----------");
                needRemoveNodes.clear();
                needRemoveNodesIndex.clear();
                if (DStack.getInstance().isFlutterApp()) {
                    //flutter工程，节点全部元素都要移除
                    needRemoveNodes.addAll(nodeList);
                    nodeList.clear();
                } else {
                    needRemoveNodes = popToRootNeedRemoveNodes();
                }
                DNode popToRootNode = getCurrentNode();
                deleteNodes();
                updateNodes();
                DActionManager.popTo(node, needRemoveNodes);
                PageLifecycleManager.pageDisappear(popToRootNode);
                DLog.logD("----------popToRoot方法结束----------");
                break;
            case DNodeActionType.DNodeActionTypePopSkip:
                DLog.logD("----------popSkip方法开始----------");
                needRemoveNodes.clear();
                needRemoveNodesIndex.clear();
                needRemoveNodes = needSkipNodes(node);
                DNode popSkipNode = getCurrentNode();
                deleteNodes();
                updateNodes();
                DActionManager.popSkip(node, needRemoveNodes);
                PageLifecycleManager.pageDisappear(popSkipNode);
                DLog.logD("----------popSkip方法结束----------");
                break;
            case DNodeActionType.DNodeActionTypeGesture:
                DLog.logD("android理论收不见flutter传来的Gesture消息");
                break;
            case DNodeActionType.DNodeActionTypeReplace:
                DLog.logD("----------replace方法开始----------");
                DNode preNode = currentNode;
                if (node.isFromFlutter()) {
                    currentNode.setTarget(node.getTarget());
                    node.setPageType(DNodePageType.DNodePageTypeFlutter);
                }
                updateNodes();
                DActionManager.replace(node);
                PageLifecycleManager.pageAppearWithReplace(preNode, currentNode);
                DLog.logD("----------replace方法结束----------");
                break;
            default:
                break;
        }
    }

    /**
     * 设置打开flutter页面时，节点对应的容器activity
     */
    private void setCurrentNodeContainer() {
        if (currentNode == null) {
            return;
        }
        if (currentNode.getPageType().equals(DNodePageType.DNodePageTypeFlutter)) {
            currentNode.setActivity(DStackActivityManager.getInstance().getTopActivity());
        }
    }

    /**
     * 处理push过来的节点
     */
    private void handlePush(DNode node) {
        boolean repeat = repeatNode(node);
        if (!repeat) {
            nodeList.add(node);
            DLog.logD("node入栈，target：" + node.getTarget());
        } else {
            DLog.logD("node入栈被去重");
        }
    }

    /**
     * 如果node信息来自flutter并且页面类型是native，那么不记录节点，由页面拦截触发
     */
    private boolean repeatNode(DNode node) {
        return node.isFromFlutter() && node.getPageType().equals(DNodePageType.DNodePageTypeNative);
    }

    /**
     * 把要返回的目标页节点后面的所有节点按顺序添加到一个集合中
     */
    private List<DNode> needRemoveNodes(DNode node) {
        List<DNode> removeNodeList = new ArrayList<>();
        boolean startAddRemoveList = true;
        int size = nodeList.size();
        for (int i = size - 1; i >= 0; i--) {
            DNode currentNode = nodeList.get(i);
            if (currentNode.getTarget().equals(node.getTarget())) {
                startAddRemoveList = false;
            }
            if (startAddRemoveList) {
                removeNodeList.add(currentNode);
                needRemoveNodesIndex.add(i);
            }
        }
        Collections.reverse(removeNodeList);
        return removeNodeList;
    }

    /**
     * native工程返回根节点，节点集合只需要保存一个元素
     */
    private List<DNode> popToRootNeedRemoveNodes() {
        List<DNode> removeNodeList = new ArrayList<>();
        boolean startAddRemoveList = true;
        int size = nodeList.size();
        for (int i = size - 1; i >= 0; i--) {
            DNode tempNode = nodeList.get(i);
            if (i == 0) {
                startAddRemoveList = false;
            }
            if (startAddRemoveList) {
                removeNodeList.add(tempNode);
                needRemoveNodesIndex.add(i);
            }
        }
        Collections.reverse(removeNodeList);
        return removeNodeList;
    }

    /**
     * 从节点列表中删除指定节点集合
     */
    private void deleteNodes() {
        DLog.logD("从节点中删除指定元素索引: " + needRemoveNodesIndex.toString());
        for (int i : needRemoveNodesIndex) {
            nodeList.remove(i);
        }
    }

    /**
     * 通过路由从混合栈底开始查找相同的节点
     */
    public DNode findNodeByRouter(String pageRouter) {
        if (TextUtils.isEmpty(pageRouter)) {
            return null;
        }
        int size = nodeList.size();
        if (size == 0) {
            return null;
        }
        for (int i = size - 1; i >= 0; i--) {
            DNode currentNode = nodeList.get(i);
            if (currentNode.getTarget().equals(pageRouter)) {
                DLog.logD("findNodeByRouter：" + pageRouter);
                return nodeList.get(i);
            }
        }
        return null;
    }

    /**
     * 需要移除的节点索引
     */
    private List<DNode> needSkipNodes(DNode node) {
        List<DNode> removeNodeList = new ArrayList<>();
        boolean startAddRemoveList;
        int size = nodeList.size();
        for (int i = size - 1; i >= 0; i--) {
            DNode currentNode = nodeList.get(i);
            //如果当前节点路由包含要skip的模块路由，则添加
            startAddRemoveList = currentNode.getTarget().contains(node.getTarget());
            if (startAddRemoveList) {
                removeNodeList.add(currentNode);
                needRemoveNodesIndex.add(i);
            } else {
                break;
            }
        }
        Collections.reverse(removeNodeList);
        return removeNodeList;
    }

    /**
     * 每次操作后，更新节点信息
     */
    public void updateNodes() {
        DLog.logD("-----更新节点开始-----");
        int size = nodeList.size();
        if (size == 0) {
            currentNode = null;
            DLog.logD("当前栈的currentNode为null");
            return;
        }
        currentNode = nodeList.get(size - 1);
        DLog.logD("当前栈的currentNode：" + currentNode.getTarget());
        DLog.logD("-----当前栈的结构开始-----");
        for (DNode node : nodeList) {
            DLog.logD(node.getPageType() + "--" + node.getTarget());
        }
        DLog.logD("-----当前栈的结构结束-----");
        DLog.logD("-----更新节点结束-----");
    }

    /**
     * 由flutter侧的didPop触发，只有flutter页面真正关闭才会收到消息
     * 当前节点的target和flutter传入的节点target一致，则删除
     */
    public void handleNeedRemoveFlutterNode(DNode node) {
        DLog.logD("----------handleNeedRemoveFlutterNode方法开始----------");
        if (nodeList.size() == 0 || currentNode == null) {
            return;
        }
        //如果当前节点的target和已经关闭的flutter页面的节点target相同，则把当前节点数据清除
        if (currentNode.getPageType().equals(DNodePageType.DNodePageTypeFlutter)) {
            if (currentNode.getTarget().equals(node.getTarget())) {
                nodeList.remove(currentNode);
                PageLifecycleManager.pageDisappear(node);
            }
        }
        updateNodes();
        DActionManager.checkNodeCritical(currentNode);
        DLog.logD("----------handleNeedRemoveFlutterNode方法结束----------");
    }

    /**
     * 当native页面关闭后，也就是activity执行了onDestroyed()之后
     * 把当前的native节点删除
     */
    public void handleNeedRemoveNativeNode(DNode node) {
        DLog.logD("----------handleNeedRemoveNativeNode方法开始----------");
        if (nodeList.size() == 0 || currentNode == null) {
            return;
        }
        if (node.isPopTo()) {
            return;
        }
        //从节点集合反向遍历第一个匹配的节点信息并移除
        DNode needRemoveNode = findNodeByRouter(node.getTarget());
        if (needRemoveNode != null) {
            nodeList.remove(needRemoveNode);
            PageLifecycleManager.pageDisappear(node);
        }
        updateNodes();
        DLog.logD("----------handleNeedRemoveNativeNode方法开始----------");
    }

    /**
     * 获取倒数第二个节点
     */
    public List<DNode> getNodeList() {
        return nodeList;
    }
}