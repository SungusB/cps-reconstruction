package com.infinity.cps.reconstruction.cpg

/**
 * The layers a [CpgEdge] can belong to, following the Code Property Graph
 * definition of Yamaguchi et al. ("Modeling and Discovering Vulnerabilities
 * with Code Property Graphs", IEEE S&P 2014): a CPG merges an **AST**, a
 * **CFG**, and a **PDG** (split here into its two constituents, **CDG** and
 * **DDG**) into one graph over a shared node set.
 *
 * [BINDS_TO] is the fusion edge this tool adds to make that shared node set
 * work when the CFG/DDG/CDG are computed over compiler-lowered bytecode
 * statements and the AST is parsed from the original source: it connects a
 * bytecode statement node to the source AST node it was compiled from. See
 * [AstBinder].
 */
enum class EdgeKind {
    CFG,
    DDG,
    CDG,
    AST,
    BINDS_TO,
}
