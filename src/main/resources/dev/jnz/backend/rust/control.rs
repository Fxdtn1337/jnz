use crate::vm::{Execution, Frame, Instruction, INVALID};

pub(crate) struct Program {
    pub seed: u32,
    pub entry: u8,
    pub tags: [u8; 5],
    pub words: [u32; 5],
}

enum Pending {
    Empty,
    Instruction(Instruction),
    Value(u64),
    Fault(Instruction, i32),
}

fn mask(seed: u32, index: usize) -> u32 {
    let mut value = seed.wrapping_add(0x7f4a7c15u32.wrapping_mul(index as u32 + 1));
    value ^= value >> 15;
    value = value.wrapping_mul(0x85ebca6b);
    value ^ (value >> 13)
}

#[inline(never)]
pub(crate) unsafe fn run(frame: *mut Frame, program: &Program) -> i32 {
    match execute(Execution::open(&mut *frame), program) {
        Ok(status) | Err(status) => status,
    }
}

fn execute(execution: Result<Execution<'_>, i32>, program: &Program) -> Result<i32, i32> {
    let mut engine = execution?;
    let mut pending = Pending::Empty;
    let mut state = program.entry as usize;
    let mut fuel = 8;
    loop {
        if fuel == 0 {
            return Err(INVALID);
        }
        fuel -= 1;
        let slot = program.words.get(state).ok_or(INVALID)?;
        let word = unsafe { std::ptr::read_volatile(slot) } ^ mask(program.seed, state);
        let [tag, next, completed, failed] = word.to_le_bytes();
        state = if tag == program.tags[0] {
            if !matches!(pending, Pending::Empty) {
                return Err(INVALID);
            }
            pending = Pending::Instruction(engine.fetch()?);
            next
        } else if tag == program.tags[1] {
            let Pending::Instruction(instruction) = pending else { return Err(INVALID) };
            fuel = 8;
            match engine.step(instruction) {
                Ok(None) => {
                    pending = Pending::Empty;
                    next
                }
                Ok(Some(value)) => {
                    pending = Pending::Value(value);
                    completed
                }
                Err(error) => {
                    pending = Pending::Fault(instruction, error);
                    failed
                }
            }
        } else if tag == program.tags[2] {
            if !matches!(pending, Pending::Empty) {
                return Err(INVALID);
            }
            next
        } else if tag == program.tags[3] {
            let Pending::Fault(instruction, error) = pending else { return Err(INVALID) };
            engine.recover(instruction, error)?;
            pending = Pending::Empty;
            next
        } else if tag == program.tags[4] {
            let Pending::Value(value) = pending else { return Err(INVALID) };
            return Ok(engine.finish(value));
        } else {
            return Err(INVALID);
        } as usize;
    }
}
