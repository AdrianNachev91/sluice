package photos.sluice.application.service;

// The unit of work JobRunner.submit() runs in the background. Takes the same JobHandle the caller
// gets back from submit(), so the work itself can poll isCancellationRequested() at its own natural
// boundaries. Declares throws Exception, like Callable<T>, so it can call straight through to a
// checked-exception engine method (ApplyEngine.apply(), VisionCuller.cull()) with no
// wrap-and-rethrow boilerplate at the call site.
@FunctionalInterface
public interface JobWork<T> {

    T run(JobHandle<T> handle) throws Exception;
}
