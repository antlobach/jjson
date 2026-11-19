; Machine-width arithmetic obligations used by native/jjson.c and
; native/jjson_chez.c. Each negated safety claim must be UNSAT.
; Scope: buffer-size/index arithmetic, not parser semantics or GC correctness.
(set-logic QF_BV)
(declare-const n (_ BitVec 64))
(declare-const i (_ BitVec 64))
(declare-const bytes (_ BitVec 64))
(define-fun max-size () (_ BitVec 64) #xffffffffffffffff)
(define-fun record-size () (_ BitVec 64) (_ bv16 64))

; record_count <= SIZE_MAX / 16 makes record_count * 16 non-wrapping.
(push)
(assert (bvule n (bvudiv max-size record-size)))
(assert (not (= (bvudiv (bvmul n record-size) record-size) n)))
(check-sat)
(pop)

; jjson_encode's count <= bytevector_length / 16 guarantees every byte of
; record i is in bounds, including its final payload byte at offset 15.
(push)
(assert (bvule n (bvudiv bytes record-size)))
(assert (bvult i n))
(assert (bvuge (bvadd (bvmul i record-size) (_ bv15 64)) bytes))
(check-sat)
(pop)

; String input: n <= (SIZE_MAX - YYJSON_PADDING_SIZE) / 4 bounds the UTF-8
; worst-case reservation. yyjson's padding size is four bytes in this version.
(push)
(assert (bvule n (bvudiv (bvsub max-size (_ bv4 64)) (_ bv4 64))))
(assert (bvult (bvadd (bvmul n (_ bv4 64)) (_ bv4 64)) (bvmul n (_ bv4 64))))
(check-sat)
(pop)

; Character pool: n <= (SIZE_MAX - 1) / 4 makes 4*n + 1 non-wrapping.
(push)
(assert (bvule n (bvudiv (bvsub max-size (_ bv1 64)) (_ bv4 64))))
(assert (bvult (bvadd (bvmul n (_ bv4 64)) (_ bv1 64)) (bvmul n (_ bv4 64))))
(check-sat)
(pop)

; String slices: length <= char_count - index, with index <= char_count,
; guarantees index + length is non-wrapping and <= char_count.
(push)
(assert (bvule i bytes))
(assert (bvule n (bvsub bytes i)))
(assert (or (bvult (bvadd i n) i) (bvugt (bvadd i n) bytes)))
(check-sat)
(pop)
