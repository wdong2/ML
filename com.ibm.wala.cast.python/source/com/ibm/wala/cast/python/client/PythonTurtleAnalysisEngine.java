package com.ibm.wala.cast.python.client;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.ibm.wala.cast.loader.AstMethod;
import com.ibm.wala.cast.python.ipa.summaries.TurtleSummary;
import com.ibm.wala.cast.tree.CAstSourcePositionMap.Position;
import com.ibm.wala.cast.util.SourceBuffer;
import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.propagation.HeapModel;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.LocalPointerKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.ipa.callgraph.propagation.PropagationCallGraphBuilder;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.types.MemberReference;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.collections.HashMapFactory;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.graph.Graph;
import com.ibm.wala.util.graph.impl.SlowSparseNumberedGraph;
import com.ibm.wala.util.intset.OrdinalSet;

public class PythonTurtleAnalysisEngine extends PythonAnalysisEngine<Graph<PythonTurtleAnalysisEngine.TurtlePath>> {

	private TurtleSummary turtles;
	
	@Override
	protected void addBypassLogic(AnalysisOptions options) {
		super.addBypassLogic(options);
		addSummaryBypassLogic(options, "turtles.xml");
		
		turtles = new TurtleSummary(getClassHierarchy());
		
		turtles.analyzeWithTurtles(options);
	}

	private List<MemberReference> makePath(CallGraph CG, CGNode node, DefUse du, int vn) {
		SSAInstruction def = du.getDef(vn);
		if (def instanceof SSAAbstractInvokeInstruction) {
			if (((SSAAbstractInvokeInstruction)def).getDeclaredTarget().getName().toString().equals("import")) {
				return Collections.singletonList(((SSAAbstractInvokeInstruction)def).getDeclaredTarget());
			} else if (CG.getPossibleTargets(node, ((SSAAbstractInvokeInstruction)def).getCallSite()).toString().contains("turtle")) {
				return makePath(CG, node, du, ((SSAAbstractInvokeInstruction)def).getReceiver());
			}
		} else if (def instanceof SSAGetInstruction) {
			List<MemberReference> stuff = new LinkedList<>(makePath(CG, node, du, ((SSAGetInstruction)def).getRef()));
			stuff.add(0, ((SSAGetInstruction)def).getDeclaredField());
			return stuff;
		} 
		
		return Collections.emptyList();
	}
	
	private static String toPathElement(MemberReference ref) {
		if (ref instanceof MethodReference) {
			return ref.getDeclaringClass().getName().toString().substring(1);					
		} else {
			return ref.getName().toString();
		}
	}
	
	public static interface TurtlePath {
		PointerKey value();
		List<MemberReference> path();
		Position position();
		List<List<MemberReference>> argument(int i);
		int arguments();
		
		default JSONObject toJSON() {
			JSONArray path = new JSONArray();
			for(MemberReference ref : path()) {
				path.put(toPathElement(ref));
			}
			
			JSONObject json = new JSONObject();
			json.put("path", path);
			try {
				json.put("expr", new SourceBuffer(position()).toString());
			} catch (JSONException | IOException e) {
				e.printStackTrace();
				assert false;
			}

			JSONArray args = new JSONArray();
			for(int i = 0; i < arguments(); i++) {
				JSONArray arg = new JSONArray();
				argument(i).forEach((elt) -> {
					JSONArray eltJson = new JSONArray();
					elt.forEach((name) -> {
						eltJson.put(toPathElement(name));
					});
					arg.put(eltJson);
				});
				args.put(arg);
			}
			json.put("args", args);
			
			return json;
		}
		
		default boolean hasSuffix(List<MemberReference> suffix) {
			List<MemberReference> path = path();
			if (suffix.size() > path.size()) {
				return false;
			} else {
				int d = path.size() - suffix.size();
				for(int i = suffix.size()-1; i >= 0; i--) {
					if (! (suffix.get(i).equals(path.get(i+d)))) {
						return false;
					}
				}
				
				return true;
			}
		}
	}
	
	@Override
	public Graph<TurtlePath> performAnalysis(PropagationCallGraphBuilder builder) throws CancelException {
		Set<TurtlePath> turtlePaths = HashSetFactory.make();
		CallGraph CG = builder.getCallGraph();
		HeapModel H = builder.getPointerAnalysis().getHeapModel();
		Map<SSAInstruction,TurtlePath> stuff = HashMapFactory.make();
		CG.getNodes(turtles.getCode().getReference()).forEach((CGNode turtle) -> {
			CG.getPredNodes(turtle).forEachRemaining((CGNode caller) -> {
				IR callerIR = caller.getIR();
				DefUse DU = caller.getDU();
				CG.getPossibleSites(caller, turtle).forEachRemaining((CallSiteReference site) -> {
					 for(SSAAbstractInvokeInstruction inst : callerIR.getCalls(site)) {
						 TurtlePath x = new TurtlePath() {
							private final List<MemberReference> path = makePath(CG, caller, DU, inst.getDef());

							public int arguments() {
								return inst.getNumberOfUses();
							}
							
							public List<List<MemberReference>> argument(int i) {
								List<List<MemberReference>> result = new LinkedList<>();
								PointerKey k = value();
								if (i <= inst.getNumberOfUses() && k instanceof LocalPointerKey) {
									LocalPointerKey lk = (LocalPointerKey)k;
									PointerKey ak = H.getPointerKeyForLocal(lk.getNode(), inst.getUse(i));
									OrdinalSet<? extends InstanceKey> ptrs = getPointerAnalysis().getPointsToSet(ak);
									for(InstanceKey ptr : ptrs) {
										if (ptr.getConcreteType().getReference().equals(TurtleSummary.turtleClassRef)) {
											ptr.getCreationSites(CG).forEachRemaining((site) -> {
												CG.getPredNodes(site.fst).forEachRemaining((caller) -> {
													CG.getPossibleSites(caller, site.fst).forEachRemaining((cs) -> {
														for(SSAAbstractInvokeInstruction call : caller.getIR().getCalls(cs)) {
															result.add(makePath(CG, caller, caller.getDU(), call.getDef()));
														}
													});
												});
											});
										}
									}
								}
								return result;
							}
							
							@Override
							public PointerKey value() {
								return builder.getPointerKeyForLocal(caller, inst.getDef());
							}

							@Override
							public List<MemberReference> path() {
								return path;
							}

							@Override
							public Position position() {
								return ((AstMethod)callerIR.getMethod()).debugInfo().getInstructionPosition(inst.iindex);
							}
							 
							@Override
							public String toString() {
								StringBuffer out = new StringBuffer();
								try {
									out.append(new SourceBuffer(((AstMethod)callerIR.getMethod()).debugInfo().getInstructionPosition(inst.iindex)));
								} catch (IOException e) {
									out.append("v").append(inst.getDef());
								}
								out.append(":");
								out.append(path());
							 	return out.toString();
							}
						};
						turtlePaths.add(x);
						stuff.put(inst, x);
					 }
				});
			});
		});
		
		Graph<TurtlePath> G = SlowSparseNumberedGraph.make();
		turtlePaths.forEach((t) -> {
			G.addNode(t);
		});
		stuff.entrySet().forEach((i) -> {
			CGNode n = ((LocalPointerKey)i.getValue().value()).getNode();
			for(int x = 0; x < i.getKey().getNumberOfUses(); x++) {
				SSAInstruction inst = i.getKey();
				PointerKey ak = H.getPointerKeyForLocal(n, inst.getUse(x));
				OrdinalSet<? extends InstanceKey> ptrs = getPointerAnalysis().getPointsToSet(ak);
				for(InstanceKey ptr : ptrs) {
					if (ptr.getConcreteType().getReference().equals(TurtleSummary.turtleClassRef)) {
						ptr.getCreationSites(CG).forEachRemaining((site) -> {
							CG.getPredNodes(site.fst).forEachRemaining((caller) -> {
								CG.getPossibleSites(caller, site.fst).forEachRemaining((cs) -> {
									for(SSAAbstractInvokeInstruction call : caller.getIR().getCalls(cs)) {
										if (stuff.containsKey(call)) {
											G.addEdge(stuff.get(call), i.getValue());
										}
									}
								});
							});
						});
					}
				}
			}
		});
		return G;
	}
}
