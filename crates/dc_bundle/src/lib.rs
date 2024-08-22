// Copyright 2024 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

use thiserror::Error;

mod utils;

pub mod android_interface;
pub mod definition;
pub mod legacy_definition;
pub mod legacy_figma_live_update;

#[derive(Error, Debug)]
pub enum Error {
    #[error("Missing field {field}")]
    MissingFieldError { field: String },
    #[error("Unknown enum variant for {enum_name}")]
    UnknownEnumVariant { enum_name: String },
}

pub type Result<T> = std::result::Result<T, Error>;
