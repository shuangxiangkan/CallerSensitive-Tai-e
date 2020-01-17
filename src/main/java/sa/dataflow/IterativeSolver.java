package sa.dataflow;

import soot.toolkits.graph.DirectedGraph;

class IterativeSolver<Domain, Result, Node> extends Solver<Domain, Result, Node> {

    @Override
    protected void solveFixedPoint(DirectedGraph<Node> cfg) {
        boolean changed;
        do {
            changed = false;
            for (Node node : cfg) {
                if (!cfg.getHeads().contains(node)) {
                    Domain in = cfg.getPredsOf(node)
                            .stream()
                            .map(outFlow::get)
                            .reduce(problem.newInitialValue(), problem::meet);
                    Domain out = problem.transfer(in, node);
                    Domain oldOut = outFlow.put(node, out);
                    if (!out.equals(oldOut)) {
                        changed = true;
                    }
                }
            }
        } while (changed);
    }
}
