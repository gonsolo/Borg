/* panic=abort and no backtraces: std and its gimli code only reference these. */
typedef unsigned long u;
int _Unwind_Backtrace(void *fn, void *arg) { (void)fn; (void)arg; return 5; }
u _Unwind_GetIP(void *c) { (void)c; return 0; }
u _Unwind_GetIPInfo(void *c, int *before) { (void)c; *before = 0; return 0; }
u _Unwind_GetCFA(void *c) { (void)c; return 0; }
u _Unwind_GetDataRelBase(void *c) { (void)c; return 0; }
u _Unwind_GetTextRelBase(void *c) { (void)c; return 0; }
u _Unwind_GetRegionStart(void *c) { (void)c; return 0; }
void *_Unwind_GetLanguageSpecificData(void *c) { (void)c; return 0; }
void *_Unwind_FindEnclosingFunction(void *pc) { (void)pc; return 0; }
void _Unwind_SetGR(void *c, int i, u v) { (void)c; (void)i; (void)v; }
void _Unwind_SetIP(void *c, u v) { (void)c; (void)v; }
