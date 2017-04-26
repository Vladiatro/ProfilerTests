# ProfilerTests

There are three test programs to see profiling performance of different approaches:

* **BreakpointsMonitoringTest** class uses Java Debug Interface (JDI) and puts breakpoints on objects constructors.
* **profiler.c** library uses JVMTI and does the same. Launched as an agent.
* **BytecodeMonitoringTest** class uses JDI with bytecode insertions with ASM library.

These programs are just for metrics, they can work only with included _net.falsetrue.Test_ and _Item_ classes
and profile only _Item_ creations count and their count in the heap in the end of Test program. 
*net.falsetrue.Test* has two integer arguments: rounds count and how many Items are added in the list in one round.

Sources are in _src_ folder. _net/falsetrue_ contains two compiled classes
for working normally when launching monitoring tests in IDEA.
Launch _net.falsetrue.BreakpointsMonitoringTest_ and _BytecodeMonitoringTest_ as normal Java programs,
but ensure they can see _net.falsetrue.Item_ and _net.falsetrue.Test_ classes.

_profiler.c_ should be compiled into a shared library. Include jdk/include and jdk/include/{platformname} before compilation.
Then run net.falsetrue.Test in this way:
`java -agentpath:agent_lib.so net.falsetrue.Test 10 1000000`.
