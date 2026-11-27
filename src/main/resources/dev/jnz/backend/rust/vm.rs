#![allow(dead_code)] // Each generated module uses its own mix of the two entry points.

use std::{cmp::Ordering, ffi::c_void, ptr, slice};

pub(crate) const INVALID: i32 = -4;
pub(crate) const DIVIDE_BY_ZERO: i32 = -2;
const SEED_BYTES: usize = std::mem::size_of::<u64>();
const INSTRUCTION_BYTES: usize = 9;
pub(crate) type Step = Result<Option<u64>, i32>;
pub type Env = *mut c_void;
pub type Reference = *mut c_void;

#[repr(C)]
pub struct Bytes {
    pub ptr: *const u8,
    pub len: usize,
}

impl Bytes {
    pub const fn new(bytes: &'static [u8]) -> Self {
        Self { ptr: bytes.as_ptr(), len: bytes.len() }
    }
}

#[repr(C)]
pub struct Program {
    pub id: u32,
    pub locals: u32,
    pub stack: u32,
    pub body: Bytes,
    pub links: Bytes,
    pub handlers: Bytes,
    pub key: *const u8,
    pub mask: *const u8,
    pub tags: *const u8,
}

#[repr(C)]
pub struct Frame {
    context: *mut c_void,
    body: Bytes,
    handlers: Bytes,
    mask: *const u8,
    tags: *const u8,
    locals: *mut u64,
    local_count: usize,
    stack: *mut u64,
    stack_count: usize,
    pub value: u64,
}

const _: [(); 88] = [(); std::mem::size_of::<Program>()];
const _: [(); 96] = [(); std::mem::size_of::<Frame>()];

pub type Runner = unsafe extern "C" fn(*mut Frame) -> i32;

extern "C" {
    pub fn jnz_rust_invoke(env: Env, program: *const Program, args: *const u64, argc: usize, runner: Runner) -> u64;
    pub fn jnz_rust_bind(env: Env, owner: Reference, id: u32, names: Bytes, key: *const u8,
                         thunks: *const *const c_void, thunk_count: usize);
    pub fn jnz_rust_bind_error(env: Env);
    fn jnz_rust_jni_step(context: *mut c_void, op: u8, x: u32, y: u32, locals: *mut u64, local_count: usize,
                         stack: *mut u64, stack_count: usize, used: *mut usize, pc: *mut usize, value: *mut u64) -> i32;
    fn jnz_rust_exception(context: *mut c_void, table: *const u8, table_size: usize, current: usize, error: i32,
                          locals: *mut u64, local_count: usize, target: *mut usize, throwable: *mut u64) -> i32;
}

pub struct Vm<'a> {
    context: *mut c_void,
    locals: &'a mut [u64],
    stack: &'a mut [u64],
    len: usize,
    pub pc: usize,
}

impl<'a> Vm<'a> {
    // The Zig ABI supplies disjoint, initialized frame ranges and keeps them live
    // for the complete Rust call. No frame references escape either backend.
    pub unsafe fn for_frame(frame: &'a mut Frame) -> Self {
        Self {
            context: frame.context,
            locals: slice::from_raw_parts_mut(frame.locals, frame.local_count),
            stack: slice::from_raw_parts_mut(frame.stack, frame.stack_count),
            len: 0,
            pc: 0,
        }
    }

    pub(crate) fn push(&mut self, value: u64) -> Result<(), i32> {
        *self.stack.get_mut(self.len).ok_or(INVALID)? = value;
        self.len += 1;
        Ok(())
    }

    pub(crate) fn pop(&mut self) -> Result<u64, i32> {
        self.len = self.len.checked_sub(1).ok_or(INVALID)?;
        Ok(self.stack[self.len])
    }

    #[inline]
    pub(crate) fn load(&mut self, index: usize) -> Result<(), i32> {
        self.push(*self.locals.get(index).ok_or(INVALID)?)
    }

    #[inline]
    pub(crate) fn store(&mut self, index: usize) -> Result<(), i32> {
        let value = self.pop()?;
        *self.locals.get_mut(index).ok_or(INVALID)? = value;
        Ok(())
    }

    #[inline]
    pub(crate) fn increment(&mut self, index: usize, amount: i32) -> Result<(), i32> {
        let local = self.locals.get_mut(index).ok_or(INVALID)?;
        *local = int((*local as i32).wrapping_add(amount));
        Ok(())
    }

    fn exception(&mut self, table: &[u8], current: usize, error: i32) -> Result<(), i32> {
        let mut throwable = 0;
        let status = unsafe {
            jnz_rust_exception(self.context, table.as_ptr(), table.len(), current, error,
                               self.locals.as_mut_ptr(), self.locals.len(), &mut self.pc, &mut throwable)
        };
        if status != 0 {
            return Err(status);
        }
        self.len = 0;
        self.push(throwable)
    }

    fn jni(&mut self, op: u8, x: u32, y: u32) -> Step {
        let mut value = 0;
        let status = unsafe {
            jnz_rust_jni_step(self.context, op, x, y, self.locals.as_mut_ptr(), self.locals.len(),
                             self.stack.as_mut_ptr(), self.stack.len(), &mut self.len, &mut self.pc, &mut value)
        };
        match status {
            0 => Ok(None),
            1 => Ok(Some(value)),
            error => Err(error),
        }
    }

    pub(crate) fn java_operation(&mut self, binding: u32) -> Result<(), i32> {
        self.jni(254, binding, 0).map(|_| ())
    }

    pub(crate) fn compare_references(&mut self, equal: bool, target: u32) -> Result<(), i32> {
        self.jni(if equal { 165 } else { 166 }, target, 0).map(|_| ())
    }

    pub(crate) fn monitor(&mut self, enter: bool) -> Result<(), i32> {
        self.jni(if enter { 194 } else { 195 }, 0, 0).map(|_| ())
    }

    pub(crate) fn throw_reference(&mut self) -> Result<u64, i32> {
        self.jni(191, 0, 0)?;
        Err(INVALID)
    }

    #[inline]
    pub fn step(&mut self, op: u8, x: u32, y: u32) -> Step {
        match op {
            0 => {}
            1 => self.push(0)?,
            9 | 14 => self.push((u64::from(y) << 32) | u64::from(x))?,
            11 | 16 => self.push(u64::from(x))?,
            21..=25 => self.load(x as usize)?,
            54..=58 => self.store(x as usize)?,
            87 => { self.pop()?; }
            89 => {
                let value = self.pop()?;
                self.push(value)?;
                self.push(value)?;
            }
            96 | 97 | 100 | 101 | 104 | 105 | 108 | 109 | 112 | 113 | 120..=131 | 148 => {
                let b = self.pop()?;
                let a = self.pop()?;
                self.push(integer(op, a, b)?)?;
            }
            98 | 99 | 102 | 103 | 106 | 107 | 110 | 111 | 114 | 115 | 149..=152 => {
                let b = self.pop()?;
                let a = self.pop()?;
                self.push(floating(op, a, b)?)?;
            }
            116..=119 | 133..=147 => {
                let value = self.pop()?;
                self.push(convert(op, value)?)?;
            }
            132 => self.increment(x as usize, y as i32)?,
            153..=158 => {
                if compare(op - 153, self.pop()? as i32, 0) { self.pc = x as usize; }
            }
            159..=164 => {
                let b = self.pop()? as i32;
                if compare(op - 159, self.pop()? as i32, b) { self.pc = x as usize; }
            }
            167 => self.pc = x as usize,
            172..=176 => return Ok(Some(self.pop()?)),
            177 => return Ok(Some(0)),
            198 | 199 => {
                if (self.pop()? == 0) == (op == 198) { self.pc = x as usize; }
            }
            165 | 166 | 191 | 194 | 195 | 254 => return self.jni(op, x, y),
            _ => return Err(INVALID),
        }
        Ok(None)
    }
}

#[inline]
fn instruction_mask(seed: u64, pc: usize) -> u64 {
    let mut mixed = seed.wrapping_add(0x9e3779b97f4a7c15u64.wrapping_mul((pc as u64).wrapping_add(1)));
    mixed = (mixed ^ (mixed >> 30)).wrapping_mul(0xbf58476d1ce4e5b9);
    mixed = (mixed ^ (mixed >> 27)).wrapping_mul(0x94d049bb133111eb);
    mixed ^ (mixed >> 31)
}

#[derive(Clone, Copy)]
pub(crate) struct Instruction {
    pc: usize,
    opcode: u8,
    x: u32,
    y: u32,
}

pub(crate) struct Execution<'a> {
    vm: Vm<'a>,
    code: &'a [u8],
    handlers: &'a [u8],
    seed: u64,
    mask: *const u8,
    tags: *const u8,
    output: &'a mut u64,
}

impl<'a> Execution<'a> {
    /// The bridge authenticates the program and supplies disjoint, live frame ranges.
    pub(crate) unsafe fn open(frame: &'a mut Frame) -> Result<Self, i32> {
        let body = slice::from_raw_parts(frame.body.ptr, frame.body.len);
        if body.len() < SEED_BYTES + INSTRUCTION_BYTES || (body.len() - SEED_BYTES) % INSTRUCTION_BYTES != 0 {
            return Err(INVALID);
        }
        let (seed, code) = body.split_at(SEED_BYTES);
        let vm = Vm {
            context: frame.context,
            locals: slice::from_raw_parts_mut(frame.locals, frame.local_count),
            stack: slice::from_raw_parts_mut(frame.stack, frame.stack_count),
            len: 0,
            pc: 0,
        };
        Ok(Self {
            vm,
            code,
            handlers: slice::from_raw_parts(frame.handlers.ptr, frame.handlers.len),
            seed: u64::from_le_bytes(seed.try_into().map_err(|_| INVALID)?),
            mask: frame.mask,
            tags: frame.tags,
            output: &mut frame.value,
        })
    }

    pub(crate) fn fetch(&mut self) -> Result<Instruction, i32> {
        let instruction_count = self.code.len() / INSTRUCTION_BYTES;
        let pc = self.vm.pc;
        if pc >= instruction_count {
            return Err(INVALID);
        }
        let instruction = &self.code[pc * INSTRUCTION_BYTES..][..INSTRUCTION_BYTES];
        let whitening = instruction_mask(self.seed, pc);
        let opcode = (instruction[0] ^ (whitening >> 56) as u8) as usize;
        let opcode = unsafe {
            ptr::read_volatile(self.mask.add(opcode)) ^ ptr::read_volatile(self.tags.add(opcode))
        };
        let x = u32::from_le_bytes([instruction[1], instruction[2], instruction[3], instruction[4]]) ^ whitening as u32;
        let y = u32::from_le_bytes([instruction[5], instruction[6], instruction[7], instruction[8]]) ^ (whitening >> 32) as u32;
        if matches!(opcode, 153..=167 | 198..=199) && x as usize >= instruction_count {
            return Err(INVALID);
        }
        self.vm.pc += 1;
        Ok(Instruction { pc, opcode, x, y })
    }

    pub(crate) fn step(&mut self, instruction: Instruction) -> Step {
        self.vm.step(instruction.opcode, instruction.x, instruction.y)
    }

    pub(crate) fn recover(&mut self, instruction: Instruction, error: i32) -> Result<(), i32> {
        self.vm.exception(self.handlers, instruction.pc, error)
    }

    pub(crate) fn finish(self, value: u64) -> i32 {
        *self.output = value;
        0
    }
}

pub unsafe extern "C" fn run_vm(frame: *mut Frame) -> i32 {
    let mut execution = match Execution::open(&mut *frame) {
        Ok(execution) => execution,
        Err(error) => return error,
    };
    loop {
        let instruction = match execution.fetch() {
            Ok(instruction) => instruction,
            Err(error) => return error,
        };
        match execution.step(instruction) {
            Ok(None) => {}
            Ok(Some(value)) => return execution.finish(value),
            Err(error) => {
                if let Err(status) = execution.recover(instruction, error) {
                    return status;
                }
            }
        }
    }
}

pub unsafe fn run_direct(frame: *mut Frame, body: fn(&mut Vm<'_>) -> Result<u64, i32>) -> i32 {
    let frame = &mut *frame;
    match body(&mut Vm::for_frame(frame)) {
        Ok(value) => { frame.value = value; 0 }
        Err(error) => error,
    }
}

fn int(value: i32) -> u64 { u64::from(value as u32) }

fn compare(op: u8, a: i32, b: i32) -> bool {
    match op {
        0 => a == b, 1 => a != b, 2 => a < b, 3 => a >= b, 4 => a > b, 5 => a <= b,
        _ => false,
    }
}

fn integer(op: u8, a: u64, b: u64) -> Result<u64, i32> {
    let (ai, bi) = (a as i32, b as i32);
    let (al, bl) = (a as i64, b as i64);
    let shift = b as u32;
    if matches!(op, 108 | 112) && bi == 0 || matches!(op, 109 | 113) && bl == 0 {
        return Err(DIVIDE_BY_ZERO);
    }
    Ok(match op {
        96 => int(ai.wrapping_add(bi)),
        97 => a.wrapping_add(b),
        100 => int(ai.wrapping_sub(bi)),
        101 => a.wrapping_sub(b),
        104 => int(ai.wrapping_mul(bi)),
        105 => a.wrapping_mul(b),
        108 => int(ai.wrapping_div(bi)),
        109 => al.wrapping_div(bl) as u64,
        112 => int(ai.wrapping_rem(bi)),
        113 => al.wrapping_rem(bl) as u64,
        120 => int(ai.wrapping_shl(shift)),
        121 => a.wrapping_shl(shift),
        122 => int(ai.wrapping_shr(shift)),
        123 => al.wrapping_shr(shift) as u64,
        124 => u64::from((a as u32).wrapping_shr(shift)),
        125 => a.wrapping_shr(shift),
        126 => int(ai & bi),
        127 => a & b,
        128 => int(ai | bi),
        129 => a | b,
        130 => int(ai ^ bi),
        131 => a ^ b,
        148 => comparison(Some(al.cmp(&bl)), 0),
        _ => return Err(INVALID),
    })
}

pub(crate) fn comparison(order: Option<Ordering>, op: u8) -> u64 {
    int(match order {
        Some(Ordering::Less) => -1,
        Some(Ordering::Equal) => 0,
        Some(Ordering::Greater) => 1,
        None if matches!(op, 149 | 151) => -1,
        None => 1,
    })
}

fn floating(op: u8, a: u64, b: u64) -> Result<u64, i32> {
    let (asingle, bsingle) = (f32::from_bits(a as u32), f32::from_bits(b as u32));
    let (adouble, bdouble) = (f64::from_bits(a), f64::from_bits(b));
    Ok(match op {
        98 => u64::from((asingle + bsingle).to_bits()),
        99 => (adouble + bdouble).to_bits(),
        102 => u64::from((asingle - bsingle).to_bits()),
        103 => (adouble - bdouble).to_bits(),
        106 => u64::from((asingle * bsingle).to_bits()),
        107 => (adouble * bdouble).to_bits(),
        110 => u64::from((asingle / bsingle).to_bits()),
        111 => (adouble / bdouble).to_bits(),
        114 => u64::from((asingle % bsingle).to_bits()),
        115 => (adouble % bdouble).to_bits(),
        149 | 150 => comparison(asingle.partial_cmp(&bsingle), op),
        151 | 152 => comparison(adouble.partial_cmp(&bdouble), op),
        _ => return Err(INVALID),
    })
}

fn convert(op: u8, value: u64) -> Result<u64, i32> {
    let (i, l) = (value as i32, value as i64);
    let (f, d) = (f32::from_bits(value as u32), f64::from_bits(value));
    Ok(match op {
        116 => int(i.wrapping_neg()),
        117 => l.wrapping_neg() as u64,
        118 => (value ^ (1 << 31)) & 0xffff_ffff,
        119 => value ^ (1 << 63),
        133 => i as i64 as u64,
        134 => u64::from((i as f32).to_bits()),
        135 => (i as f64).to_bits(),
        136 => int(i),
        137 => u64::from((l as f32).to_bits()),
        138 => (l as f64).to_bits(),
        139 => int(f as i32),
        140 => f as i64 as u64,
        141 => (f as f64).to_bits(),
        142 => int(d as i32),
        143 => d as i64 as u64,
        144 => u64::from((d as f32).to_bits()),
        145 => int(value as i8 as i32),
        146 => u64::from(value as u16),
        147 => int(value as i16 as i32),
        _ => return Err(INVALID),
    })
}
