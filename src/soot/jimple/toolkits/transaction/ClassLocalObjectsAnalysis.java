package soot.jimple.toolkits.transaction;

import soot.*;
import soot.util.*;
import java.util.*;
import soot.toolkits.mhp.*;
import soot.toolkits.graph.*;
import soot.toolkits.scalar.*;
import soot.jimple.toolkits.callgraph.*;
import soot.tagkit.*;
import soot.jimple.internal.*;
import soot.jimple.*;
import soot.jimple.spark.sets.*;
import soot.jimple.spark.pag.*;
import soot.toolkits.scalar.*;

// ClassLocalObjectsAnalysis written by Richard L. Halpert, 2007-02-23
// Finds objects that are local to the given scope.
// NOTE THAT THIS ANALYSIS'S RESULTS DO NOT APPLY TO SUBCLASSES OF THE SCOPE CLASS

public class ClassLocalObjectsAnalysis
{
	LocalObjectsAnalysis loa;
	DataFlowAnalysis dfa;
	SootClass sootClass;

	ClassLocalObjectsAnalysis context;
	
	Map methodToMethodLocalObjectsAnalysis;
	
	ArrayList localFields;
	ArrayList sharedFields;
	
	/** Constructor for when this is the class that defines the scope for "Local" */
	public ClassLocalObjectsAnalysis(LocalObjectsAnalysis loa, DataFlowAnalysis dfa, SootClass sootClass)
	{
		 this.loa = loa;
		 this.dfa = dfa;
		 this.sootClass = sootClass;

		 this.context = null;
		 
		 methodToMethodLocalObjectsAnalysis = new HashMap();
		 
		 localFields = new ArrayList();
		 sharedFields = new ArrayList();
		 
		 doAnalysis();
	}
	
/*
	private ClassLocalObjectsAnalysis(SootClass sootClass, DataFlowAnalysis dfa, ClassLocalObjectsAnalysis context)
	{
		 this.sootClass = sootClass;
		 this.dfa = dfa;
		 this.context = context;
		 
		 localFields = new ArrayList();
		 sharedFields = new ArrayList();
		 
		 doAnalysis();
	}
*/
	
	private void doAnalysis()
	{
		// Combine the DFA results for each of this class's methods, using safe
		// approximations for which parameters, fields, and globals are shared
		// or local.  These approximations could be calculated by an analysis.
		// Methods that aren't called in this program can be excluded.
		
		// Determine which methods are reachable in this program
		ReachableMethods rm = Scene.v().getReachableMethods();

		// Get list of reachable methods declared in this class
		// Also get list of fields declared in this class
		List scopeMethods = new ArrayList();
		List scopeFields = new ArrayList();
		Iterator scopeMethodsIt = sootClass.methodIterator();
		while(scopeMethodsIt.hasNext())
		{
			SootMethod scopeMethod = (SootMethod) scopeMethodsIt.next();
			if(rm.contains(scopeMethod))
				scopeMethods.add(scopeMethod);
		}
		Iterator scopeFieldsIt = sootClass.getFields().iterator();
		while(scopeFieldsIt.hasNext())
		{
			SootField scopeField = (SootField) scopeFieldsIt.next();
			scopeFields.add(scopeField);
		}
		
		// Add reachable methods and fields declared in superclasses
		SootClass superclass = sootClass;
		if(superclass.hasSuperclass())
			superclass = sootClass.getSuperclass();
		while(superclass.hasSuperclass()) // we don't want to process Object
		{
	        Iterator scMethodsIt = superclass.methodIterator();
	        while(scMethodsIt.hasNext())
	        {
				SootMethod scMethod = (SootMethod) scMethodsIt.next();
				if(rm.contains(scMethod))
					scopeMethods.add(scMethod);
	        }
	        Iterator scFieldsIt = superclass.getFields().iterator();
	        while(scFieldsIt.hasNext())
	        {
				SootField scField = (SootField) scFieldsIt.next();
				
				scopeFields.add(scField);
	        }
			superclass = superclass.getSuperclass();
		}
		
		// Separate fields into shared and local.  Initially, all fields are local.
		// Methods are iterated over, moving fields to shared if shared data flows to them.
		// This is repeated until no fields move for a complete iteration.
		
		// Populate localFields and sharedFields with SootFields
		Iterator fieldsIt = scopeFields.iterator();
		while(fieldsIt.hasNext())
		{
			SootField field = (SootField) fieldsIt.next();
			if( fieldIsInitiallyLocal(field) )
				localFields.add(field);
			else
				sharedFields.add(field);
		}
		
		// Propagate (aka iterate iterate iterate iterate! hope it's not too slow)
		boolean changed = true;
		while(changed)
		{
			changed = false;
//			G.v().out.println("Starting iteration:");
			Iterator methodsIt = scopeMethods.iterator();
			while(methodsIt.hasNext())
			{
				SootMethod method = (SootMethod) methodsIt.next();
				// we can't learn anything from non-concrete methods, and statics can't write non-static fields
				if(method.isStatic() || !method.isConcrete())
					continue;
				Iterator localFieldsIt = ((List) localFields.clone()).iterator(); // unbacked iterator so we can remove from the original
				boolean printedMethodHeading = false;
				while(localFieldsIt.hasNext())
				{
					SootField localField = (SootField) localFieldsIt.next();
					List sourcesAndSinks = new ArrayList();
					List sources = dfa.getSourcesOf(method, dfa.getEquivalentValueFieldRef(method, localField));
					List sinks = dfa.getSinksOf(method, dfa.getEquivalentValueFieldRef(method, localField));
					sourcesAndSinks.addAll(sources);
					sourcesAndSinks.addAll(sinks);
					Iterator sourcesAndSinksIt = sourcesAndSinks.iterator();
					if(localField.getDeclaringClass().isApplicationClass() &&
					   sourcesAndSinksIt.hasNext())
					{
//						if(!printedMethodHeading)
//						{
//							G.v().out.println("    Method: " + method.toString());
//							printedMethodHeading = true;
//						}
//						G.v().out.println("        Field: " + localField.toString());
					}
					while(sourcesAndSinksIt.hasNext())
					{
						EquivalentValue sourceOrSink = (EquivalentValue) sourcesAndSinksIt.next();
						Ref sourceOrSinkRef = (Ref) sourceOrSink.getValue();
						boolean fieldBecomesShared = false;
						if(sourceOrSinkRef instanceof ParameterRef) // or return ref
						{
							fieldBecomesShared = !parameterIsLocal(method, sourceOrSink);
						}
						else if(sourceOrSinkRef instanceof ThisRef) // or return ref
						{
							fieldBecomesShared = !thisIsLocal(method, sourceOrSink);
						}
						else if(sourceOrSinkRef instanceof InstanceFieldRef)
						{
							fieldBecomesShared = sharedFields.contains(sourceOrSink);
						}
						else if(sourceOrSinkRef instanceof StaticFieldRef)
						{
							fieldBecomesShared = true;
						}
						else
						{
							throw new RuntimeException("Unknown type of Ref in Data Flow Graph:");
						}
						
						if(fieldBecomesShared)
						{
//							if(localField.getDeclaringClass().isApplicationClass())
//								G.v().out.println("            Source/Sink: " + sourceOrSinkRef.toString() + " is SHARED");
							localFields.remove(localField);
							sharedFields.add(localField);
							changed = true;
							break; // other sources don't matter now... it only takes one to taint the field
						}
						else
						{
//							if(localField.getDeclaringClass().isApplicationClass())
//								G.v().out.println("            Source: " + sourceRef.toString() + " is local");
						}
					}
				}
			}
		}
		
		// DEBUG: Print out the resulting list!
//		G.v().out.println("Found local/shared fields for " + sootClass.toString());
//		G.v().out.println("    Local fields: ");
		Iterator localsToPrintIt = localFields.iterator();
		while(localsToPrintIt.hasNext())
		{
			SootField localToPrint = (SootField) localsToPrintIt.next();
//			if(localToPrint.getDeclaringClass().isApplicationClass())
//				G.v().out.println("                  " + localToPrint);
		}
//		G.v().out.println("    Shared fields: ");
		Iterator sharedsToPrintIt = sharedFields.iterator();
		while(sharedsToPrintIt.hasNext())
		{
			SootField sharedToPrint = (SootField) sharedsToPrintIt.next();
//			if(sharedToPrint.getDeclaringClass().isApplicationClass())
//				G.v().out.println("                  " + sharedToPrint);
		}
				
		// Analyze each method: determine which Locals are local and which are shared
		Iterator it = sootClass.getMethods().iterator();
		while(it.hasNext())
		{
			SootMethod method = (SootMethod) it.next();
			
			// For each method, analyze the body
			Body b = method.retrieveActiveBody();
			UnitGraph g = new ExceptionalUnitGraph(b);
			MethodLocalObjectsAnalysis mloa = new MethodLocalObjectsAnalysis(g, this, dfa);
			methodToMethodLocalObjectsAnalysis.put(method, mloa);
		}
	}
	
	public boolean isObjectLocal(Value localOrRef, SootMethod sm)
	{
		if(localOrRef instanceof StaticFieldRef)
		{
			return false;
		}
		
		MethodLocalObjectsAnalysis mloa = getMethodLocalObjectsAnalysis(sm);
		if(localOrRef instanceof InstanceFieldRef)
		{
			InstanceFieldRef ifr = (InstanceFieldRef) localOrRef;
			if( ifr.getBase().equivTo(mloa.getThisLocal()) )
				return fieldIsLocal(ifr.getFieldRef().resolve());
			else
			{
				// if referred object is local, then find out if field is local in that object
				if(isObjectLocal(ifr.getBase(), sm))
				{
					return loa.isFieldLocal(ifr.getFieldRef().resolve());
				}
				else
					return false;
			}
		}
		return mloa.isObjectLocal(localOrRef);
	}
	
	public MethodLocalObjectsAnalysis getMethodLocalObjectsAnalysis(SootMethod sm)
	{
		return (MethodLocalObjectsAnalysis) methodToMethodLocalObjectsAnalysis.get(sm);
	}
	
	// Should check field access rights, and possibly perform an analysis
	// to determine if a field that is accessible is ever directly accessed
	private boolean fieldIsInitiallyLocal(SootFieldRef fieldRef)
	{
		return fieldIsInitiallyLocal(fieldRef.resolve());
	}
	
	private boolean fieldIsInitiallyLocal(SootField field)
	{
		if(field.isStatic() && !field.isPrivate() && field.getType() instanceof RefType)
			return false;
		return true;
		
		// public fields require a whole-program search for accesses outside of this class
		//  - beware reentrant behavior, as a ClassLocalObjectsAnalysis will be required to analyze these accesses if found
		// protected and package-private fields require a this-package search for accesses outside of this class
		//  - beware reentrant behavior, as a ClassLocalObjectsAnalysis will be required to analyze these accesses if found
		// private fields are assumed local
		// NOTE THAT THIS ANALYSIS'S RESULTS DO NOT APPLY TO SUBCLASSES OF THE SCOPE CLASS
	}
	
	// Should perform a Local-Inputs analysis on the callsites to this method
	private boolean parameterIsInitiallyLocal(SootMethod method, EquivalentValue parameterRef) // can also be return value
	{
		return false;
	}
	
	protected List getSharedFields()
	{
		return (List) sharedFields.clone();
	}
	
	protected List getLocalFields()
	{
		return (List) localFields.clone();
	}
	
	protected boolean fieldIsLocal(SootField field)
	{
		return localFields.contains(field);
	}
	
	protected boolean fieldIsLocal(EquivalentValue fieldRef)
	{
		return localFields.contains( ((SootFieldRef) fieldRef.getValue()).resolve() );
	}	
	
	protected boolean parameterIsLocal(SootMethod method, EquivalentValue parameterRef)
	{
//		if(context != null)
//		{
//			return context.
//		}
		return false;
	}
	
	protected boolean thisIsLocal(SootMethod method, EquivalentValue thisRef)
	{
		return true;
	}
}

